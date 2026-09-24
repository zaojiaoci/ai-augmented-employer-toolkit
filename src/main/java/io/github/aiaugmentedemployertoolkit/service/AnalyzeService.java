package io.github.aiaugmentedemployertoolkit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.TransitionPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    private final ChatClient analyzeChatClient;
    private final ChatClient streamingChatClient;
    private final ConversationMemoryStore memory;
    private final ObjectMapper objectMapper;
    private final BeanOutputConverter<AnalyzeResponse> outputConverter;

    public AnalyzeService(
            @Qualifier("analyzeChatClient") ChatClient analyzeChatClient,
            @Qualifier("streamingChatClient") ChatClient streamingChatClient,
            ConversationMemoryStore memory) {
        this.analyzeChatClient = analyzeChatClient;
        this.streamingChatClient = streamingChatClient;
        this.memory = memory;
        this.objectMapper = new ObjectMapper();
        this.outputConverter = new BeanOutputConverter<>(AnalyzeResponse.class);
    }

    /**
     * 同步分析（原有接口，使用 BeanOutputConverter 替代手动 JSON 解析）
     */
    public AnalyzeResponse analyze(String jobDescription) {
        log.info("开始分析岗位描述，长度: {} 字符", jobDescription.length());

        String formatInstructions = outputConverter.getFormat();
        String rawResponse = analyzeChatClient.prompt()
                .user(jobDescription + "\n\n" + formatInstructions)
                .call()
                .content();

        log.debug("LLM 原始返回: {}", rawResponse);

        return parseResponse(rawResponse);
    }

    /**
     * 流式分析（SSE），支持多轮追问。
     * <p>
     * 首次分析（无历史）时追加 JSON 格式指令，确保 LLM 返回结构化 JSON；
     * 追问时（有历史）不加格式指令，LLM 返回自由文本。
     */
    public Flux<String> analyzeStream(String sessionId, String userText) {
        List<Message> history = memory.history(sessionId);
        StringBuilder full = new StringBuilder();

        // 首次分析追加格式指令，追问时不加
        String actualInput = history.isEmpty()
                ? userText + "\n\n请严格按照以下 JSON 格式返回结果：\n" + outputConverter.getFormat()
                : userText;

        return streamingChatClient.prompt()
                .messages(history)
                .user(actualInput)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token != null) {
                        full.append(token);
                    }
                })
                .doOnComplete(() -> {
                    String reply = full.toString().trim();
                    memory.append(sessionId, userText, reply);
                    log.debug("流式分析完成，sessionId: {}, 回复长度: {}", sessionId, reply.length());
                });
    }

    /**
     * 解析 LLM 返回的文本为 AnalyzeResponse。
     * 优先使用 BeanOutputConverter，失败则回退到手动 JSON 解析。
     */
    private AnalyzeResponse parseResponse(String rawResponse) {
        // 优先尝试 BeanOutputConverter
        try {
            return outputConverter.convert(rawResponse);
        } catch (Exception e) {
            log.debug("BeanOutputConverter 解析失败，尝试手动 JSON 解析: {}", e.getMessage());
        }

        // 回退到手动 JSON 解析
        return parseJsonManually(rawResponse);
    }

    private AnalyzeResponse parseJsonManually(String rawResponse) {
        try {
            String json = extractJson(rawResponse);
            JsonNode node = objectMapper.readTree(json);

            double ratio = Math.max(0.0, Math.min(1.0,
                    node.path("automationRatio").asDouble(0.5)));
            String summary = node.path("summary").asText("无法解析分析结果");
            TransitionPath path = parseTransitionPath(node.path("transitionPath"));

            return new AnalyzeResponse(ratio, summary, path);
        } catch (Exception e) {
            log.warn("解析 LLM 返回 JSON 失败，使用原始文本作为 summary: {}", e.getMessage());
            TransitionPath fallback = new TransitionPath(
                    List.of("请重新分析以获取转岗建议"),
                    "待评估",
                    "待评估",
                    "每个岗位都有可迁移的核心价值，转岗不是从零开始。"
            );
            return new AnalyzeResponse(0.5, rawResponse, fallback);
        }
    }

    private TransitionPath parseTransitionPath(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new TransitionPath(
                    List.of("请重新分析以获取转岗建议"),
                    "待评估",
                    "待评估",
                    "每个岗位都有可迁移的核心价值，转岗不是从零开始。"
            );
        }

        List<String> skills = new ArrayList<>();
        JsonNode skillsNode = node.path("transferableSkills");
        if (skillsNode.isArray()) {
            skillsNode.forEach(s -> skills.add(s.asText()));
        }

        return new TransitionPath(
                skills.isEmpty() ? List.of("待补充") : skills,
                node.path("suggestedRole").asText("待评估"),
                node.path("skillGap").asText("待评估"),
                node.path("encouragement").asText("你的经验是宝贵的资产，转岗是在此之上叠加新能力。")
        );
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

}
