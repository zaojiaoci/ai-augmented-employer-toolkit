package io.github.aiaugmentedemployertoolkit.service;

import com.alibaba.cloud.ai.evaluation.AnswerFaithfulnessEvaluator;
import com.alibaba.cloud.ai.evaluation.AnswerRelevancyEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答质量评估（LLM-as-a-judge）。
 * <p>
 * 解决两个真实痛点：
 * 1. 改一次提示词不知道效果变好还是变坏——现在有量化指标了；
 * 2. 知识库里存在示意性数据，需要检验「回答是否忠实于检索到的上下文」，
 *    这正是 {@code AnswerFaithfulnessEvaluator} 能测的。
 * <p>
 * 使用 spring-ai-alibaba 提供的 {@link AnswerRelevancyEvaluator} 与
 * {@link AnswerFaithfulnessEvaluator}：它们以「标准答案 vs 学生答案」的方式让另一个 LLM 打分，
 * 这里把「检索到的知识库上下文」当作标准答案、「模型产出的分析」当作学生答案。
 * <p>
 * 默认关闭（{@code app.evaluation.enabled=false}）：每次评估要额外调用两次 LLM，
 * 属于开发/CI 阶段的能力，不应默认发生在用户请求链路上。
 */
@Service
public class AnalysisEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEvaluator.class);

    private final ChatModel chatModel;
    private final boolean enabled;

    public AnalysisEvaluator(ChatModel chatModel,
                             @Value("${app.evaluation.enabled:false}") boolean enabled) {
        this.chatModel = chatModel;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 评估一次分析输出。
     *
     * @param jobDescription   原始岗位描述（作为 question）
     * @param retrievedDocs    检索到的知识库文档（作为 ground truth）
     * @param answer           模型产出的分析内容（作为 student answer）
     */
    public Result evaluate(String jobDescription, List<Document> retrievedDocs, String answer) {
        if (!enabled) {
            throw new IllegalStateException("评估功能未开启，请设置 app.evaluation.enabled=true");
        }
        List<Document> docs = retrievedDocs == null ? List.of() : retrievedDocs;
        String studentAnswer = answer == null ? "" : answer;

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        EvaluationRequest request = new EvaluationRequest(jobDescription, docs, studentAnswer);

        EvaluationResponse relevancy = new AnswerRelevancyEvaluator(builder).evaluate(request);
        EvaluationResponse faithfulness = new AnswerFaithfulnessEvaluator(builder).evaluate(request);

        Result result = new Result(relevancy.getScore(), relevancy.getFeedback(),
                faithfulness.getScore(), faithfulness.getFeedback());

        log.info("回答质量评估：相关性={} 忠实度={}", result.relevancyScore(), result.faithfulnessScore());
        return result;
    }

    /**
     * 纯文本版本的检索上下文，便于测试与外部调用。
     * 不能与 {@link #evaluate(String, List, String)} 同名——两者擦除后签名相同。
     */
    public Result evaluateWithTexts(String jobDescription, List<String> retrievedTexts, String answer) {
        List<Document> docs = new ArrayList<>();
        if (retrievedTexts != null) {
            retrievedTexts.forEach(text -> docs.add(new Document(text)));
        }
        return evaluate(jobDescription, docs, answer);
    }

    public record Result(float relevancyScore,
                         String relevancyFeedback,
                         float faithfulnessScore,
                         String faithfulnessFeedback) {

        public boolean pass() {
            return relevancyScore > 0 && faithfulnessScore > 0;
        }

        public float averageScore() {
            return (relevancyScore + faithfulnessScore) / 2.0f;
        }
    }
}
