package io.github.aiaugmentedemployertoolkit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.TransitionPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    private final ChatClient analyzeChatClient;
    private final ObjectMapper objectMapper;

    public AnalyzeService(ChatClient analyzeChatClient) {
        this.analyzeChatClient = analyzeChatClient;
        this.objectMapper = new ObjectMapper();
    }

    public AnalyzeResponse analyze(String jobDescription) {
        log.info("开始分析岗位描述，长度: {} 字符", jobDescription.length());

        String rawResponse = analyzeChatClient.prompt()
                .user(jobDescription)
                .call()
                .content();

        log.debug("LLM 原始返回: {}", rawResponse);

        return parseResponse(rawResponse);
    }

    private AnalyzeResponse parseResponse(String rawResponse) {
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
