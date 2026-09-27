package io.github.aiaugmentedemployertoolkit.config;

import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import com.alibaba.cloud.ai.model.RerankModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG 检索 Advisor 工厂，负责「能力分级」。
 * <p>
 * 默认是 Spring AI 原生的 {@link QuestionAnswerAdvisor}，任何模型厂商都能跑；
 * 只有显式开启 {@code app.rag.rerank-enabled=true} 且容器中存在
 * {@link RerankModel}（由 spring-ai-alibaba 的 DashScope rerank 自动配置提供）时，
 * 才升级为「粗召回 + DashScope rerank 重排」。
 * <p>
 * 这样 Spring AI Alibaba 的增强能力是可选的：关掉也能跑，不会因为缺 rerank 模型而启动失败。
 */
@Component
public class RagAdvisorFactory {

    private static final Logger log = LoggerFactory.getLogger(RagAdvisorFactory.class);

    /**
     * 重排后的上下文注入模板。占位符 {@code {query}} 与 {@code {question_answer_context}}
     * 由 {@link RetrievalRerankAdvisor} 填充，这里沿用官方结构、把引导语换成中文并保留引用要求。
     */
    private static final PromptTemplate RERANK_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            Context information is below, surrounded by ---------------------
            ---------------------
            {question_answer_context}
            ---------------------
            请基于上述上下文回答，不要使用上下文之外的知识。
            如果上下文中没有答案，请明确说明无法回答。引用了上下文信息时，请在句末用括号标注来源文件名。
            """);

    private final VectorStore vectorStore;
    private final ObjectProvider<RerankModel> rerankModelProvider;
    private final boolean rerankEnabled;
    private final int topK;
    private final double similarityThreshold;
    private final int rerankTopK;
    private final double rerankMinScore;

    public RagAdvisorFactory(VectorStore vectorStore,
                             ObjectProvider<RerankModel> rerankModelProvider,
                             @Value("${app.rag.rerank-enabled:false}") boolean rerankEnabled,
                             @Value("${app.rag.top-k:5}") int topK,
                             @Value("${app.rag.similarity-threshold:0.6}") double similarityThreshold,
                             @Value("${app.rag.rerank-top-k:20}") int rerankTopK,
                             @Value("${app.rag.rerank-min-score:0.3}") double rerankMinScore) {
        this.vectorStore = vectorStore;
        this.rerankModelProvider = rerankModelProvider;
        this.rerankEnabled = rerankEnabled;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.rerankTopK = rerankTopK;
        this.rerankMinScore = rerankMinScore;
    }

    public Advisor create() {
        if (!rerankEnabled) {
            return questionAnswerAdvisor();
        }
        RerankModel rerankModel = rerankModelProvider.getIfAvailable();
        if (rerankModel == null) {
            log.warn("已开启 app.rag.rerank-enabled，但容器中不存在 RerankModel，降级为普通向量检索");
            return questionAnswerAdvisor();
        }
        log.info("RAG 启用 rerank 重排：召回 top-{}，重排后保留评分 >= {} 的文档", rerankTopK, rerankMinScore);
        return new RetrievalRerankAdvisor(vectorStore, rerankModel, rerankSearchRequest(),
                RERANK_PROMPT_TEMPLATE, Double.valueOf(rerankMinScore));
    }

    private Advisor questionAnswerAdvisor() {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(similarityThreshold)
                        .topK(topK)
                        .build())
                .build();
    }

    private SearchRequest rerankSearchRequest() {
        // 先放宽向量检索的阈值与条数，把筛选工作交给 rerank 模型
        return SearchRequest.builder()
                .similarityThreshold(0.0)
                .topK(rerankTopK)
                .build();
    }
}
