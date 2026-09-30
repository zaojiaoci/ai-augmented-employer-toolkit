package io.github.aiaugmentedemployertoolkit.controller;

import io.github.aiaugmentedemployertoolkit.dto.AnalyzeRequest;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.EvaluateRequest;
import io.github.aiaugmentedemployertoolkit.dto.TransitionPath;
import io.github.aiaugmentedemployertoolkit.service.AnalysisEvaluator;
import io.github.aiaugmentedemployertoolkit.service.AnalyzeService;
import io.github.aiaugmentedemployertoolkit.service.ConversationMemory;
import io.github.aiaugmentedemployertoolkit.service.KnowledgeBaseService;
import io.github.aiaugmentedemployertoolkit.service.LlmUsageMetrics;
import io.github.aiaugmentedemployertoolkit.service.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @description: 岗位自动化分析接口
 * @author: zaojiaoci
 * @date: 2026年09月24日 23:24:13
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final AnalyzeService analyzeService;
    private final AnalysisEvaluator analysisEvaluator;
    private final KnowledgeBaseService knowledgeBase;
    private final LlmUsageMetrics metrics;
    private final ConversationMemory conversationMemory;

    public AnalyzeController(AnalyzeService analyzeService,
                             AnalysisEvaluator analysisEvaluator,
                             KnowledgeBaseService knowledgeBase,
                             LlmUsageMetrics metrics,
                             ConversationMemory conversationMemory) {
        this.analyzeService = analyzeService;
        this.analysisEvaluator = analysisEvaluator;
        this.knowledgeBase = knowledgeBase;
        this.metrics = metrics;
        this.conversationMemory = conversationMemory;
    }

    /**
     * 清空指定会话的对话上下文（保留长期记忆/知识库）。
     * <p>
     * 供外部评测工具调用，用于「跨会话」评测：注入事实 → 清空上下文 → 再提问，
     * 以此区分「上下文窗口还记得」与「长期记忆真的记住了」。
     */
    @DeleteMapping("/memory/{sessionId}")
    public ResponseEntity<Void> clearMemory(@PathVariable String sessionId) {
        conversationMemory.clear(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 同步分析端点
     */
    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        return analyzeService.analyze(jobDescriptionOf(request));
    }

    /**
     * 流式分析端点（SSE），支持打字机效果和多轮追问。
     * <p>
     * 事件分两类：普通正文片段，以及最后一条以 {@code [[RESULT]]} 开头的结构化结果。
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeStream(
            @RequestBody AnalyzeRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        String sid = sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
        return analyzeService.analyzeStream(sid, jobDescriptionOf(request));
    }

    /**
     * LLM 调用与 token 消耗统计。
     */
    @GetMapping("/usage")
    public LlmUsageMetrics.Snapshot usage() {
        return metrics.snapshot();
    }

    /**
     * 知识库来源及其可信度等级，供前端把"示意数据"的引用渲染成警示样式。
     */
    @GetMapping("/knowledge/sources")
    public Map<String, String> knowledgeSources() {
        return knowledgeBase.credibilityBySource();
    }

    /**
     * 回答质量评估（默认关闭）。
     * <p>
     * 用于改动提示词后做回归对比：给定同一批岗位描述，比较改前改后的相关性 / 忠实度分数。
     */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody EvaluateRequest request) {
        if (!analysisEvaluator.isEnabled()) {
            return error(HttpStatus.CONFLICT, "评估功能未开启，请设置 app.evaluation.enabled=true");
        }
        String jobDescription = request == null ? null : request.getJobDescription();
        if (jobDescription == null || jobDescription.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "jobDescription 不能为空");
        }

        String answer = request.getAnswer();
        AnalyzeResponse analyzed = null;
        if (answer == null || answer.isBlank()) {
            analyzed = analyzeService.analyze(jobDescription);
            answer = analyzed.getSummary();
        }
        String transition = request.getTransitionContext();
        if ((transition == null || transition.isBlank()) && analyzed != null) {
            transition = renderTransition(analyzed.getTransitionPath());
        }
        return ResponseEntity.ok(analysisEvaluator.evaluateWithTexts(jobDescription, request.getDocuments(), answer, transition));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyRequests(RateLimitExceededException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamFailure(IllegalStateException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static String jobDescriptionOf(AnalyzeRequest request) {
        return request == null ? null : request.getJobDescription();
    }

    /**
     * 把转岗路径拼成评委可读的文本（建议岗位 + 能力缺口 + 鼓励语）。
     */
    private static String renderTransition(TransitionPath path) {
        if (path == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (path.getSuggestedRole() != null) {
            sb.append("建议转岗方向：").append(path.getSuggestedRole()).append("\n");
        }
        if (path.getSkillGap() != null) {
            sb.append("能力缺口：").append(path.getSkillGap()).append("\n");
        }
        if (path.getEncouragement() != null) {
            sb.append("鼓励语：").append(path.getEncouragement());
        }
        return sb.toString();
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
