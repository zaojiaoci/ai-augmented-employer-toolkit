package io.github.aiaugmentedemployertoolkit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 转岗建议质量评测（LLM-as-judge 的第三个维度）。
 * <p>
 * 此前 {@link AnalysisEvaluator} 只评「分析」的相关性 / 忠实度，而最终受益人（员工）最关心、
 * 也最能体现项目「增强而非替代」价值观的 {@code transitionPath}（可迁移技能 / 目标岗位 / 能力缺口）
 * 完全没有被量化。本评测用另一个 LLM 按 rubric 给转岗建议打分，维度包括：
 * <ul>
 *   <li>一致性：建议岗位是否真正来自可迁移技能，而非凭空推荐；</li>
 *   <li>可操作性：能力缺口是否具体，而非空泛建议；</li>
 *   <li>立场：是否升级式而非重来式，是否出现替代 / 裁员式表述。</li>
 * </ul>
 * 与相关性 / 忠实度一样，这是「相对基线」而非绝对质量分：改动前后各跑一次对比均值。
 * <p>
 * 评委输出无法解析时降级为 0.5（中性），不阻断主评测链路。
 */
public class TransitionQualityJudge {

    private static final Logger log = LoggerFactory.getLogger(TransitionQualityJudge.class);

    private static final String RUBRIC = """
            你是一位资深的职业转型规划评审专家。下面给定「岗位描述」「任务拆解」和「转岗建议」，
            请评估这条转岗建议的质量，只输出一个 JSON，不要输出其他内容。

            评估维度与权重：
            1. 一致性（40%）：建议的目标岗位是否真正源自该岗位的可迁移技能？还是凭空推荐、与技能无关？
            2. 可操作性（35%）：能力缺口（skillGap）是否具体、可执行（指明明确的学习方向或证书/系统），
               而不是「需要提升综合能力」这类空泛表述？
            3. 立场（25%）：措辞是「升级而非重来」的鼓励式，还是出现了「被替代 / 裁员 / 淘汰」式表述？

            输出 JSON 格式（严格遵守）：
            {"score": 0.0到1.0之间的数值, "feedback": "一句话说明扣分点，用中文"}

            评分标准：三项都做得好给 0.8~1.0；有一项明显欠缺给 0.4~0.7；多项缺失或立场失当给 0~0.3。
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TransitionQualityJudge(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public record Result(float score, String feedback) {
    }

    /**
     * 评估转岗建议质量。
     *
     * @param jobDescription       原始岗位描述（提供岗位语境）
     * @param transitionText       转岗建议的可读文本（建议岗位 + 能力缺口 + 鼓励语拼接）
     * @param taskBreakdownText    任务拆解文本（作为一致性判断的参照）
     */
    public Result judge(String jobDescription, String transitionText, String taskBreakdownText) {
        if (transitionText == null || transitionText.isBlank()) {
            return new Result(0.5f, "未提供转岗建议文本，跳过转岗质量评测");
        }
        String user = "岗位描述：\n" + (jobDescription == null ? "" : jobDescription) + "\n\n"
                + "任务拆解：\n" + (taskBreakdownText == null ? "" : taskBreakdownText) + "\n\n"
                + "转岗建议：\n" + transitionText;

        try {
            Message system = new SystemMessage(RUBRIC);
            Message userMessage = new UserMessage(user);
            ChatResponse response = chatModel.call(new Prompt(List.of(system, userMessage)));
            String text = AnalysisEvaluator.judgeTextOf(response);
            JsonNode node = objectMapper.readTree(text);
            double raw = node.path("score").asDouble(0.5);
            float score = (float) Math.max(0.0, Math.min(1.0, raw));
            String feedback = node.path("feedback").asText("无反馈");
            return new Result(score, feedback);
        } catch (Exception e) {
            log.warn("转岗建议质量评测：评委输出无法解析，降级为中性分（{}）", e.getMessage());
            return new Result(0.5f, "评委输出无法解析，转岗建议未评分");
        }
    }
}
