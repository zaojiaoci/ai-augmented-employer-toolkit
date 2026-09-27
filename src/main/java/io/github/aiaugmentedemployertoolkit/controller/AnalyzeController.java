package io.github.aiaugmentedemployertoolkit.controller;

import io.github.aiaugmentedemployertoolkit.dto.AnalyzeRequest;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.EvaluateRequest;
import io.github.aiaugmentedemployertoolkit.service.AnalysisEvaluator;
import io.github.aiaugmentedemployertoolkit.service.AnalyzeService;
import io.github.aiaugmentedemployertoolkit.service.KnowledgeBaseService;
import io.github.aiaugmentedemployertoolkit.service.LlmUsageMetrics;
import io.github.aiaugmentedemployertoolkit.service.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    public AnalyzeController(AnalyzeService analyzeService,
                             AnalysisEvaluator analysisEvaluator,
                             KnowledgeBaseService knowledgeBase,
                             LlmUsageMetrics metrics) {
        this.analyzeService = analyzeService;
        this.analysisEvaluator = analysisEvaluator;
        this.knowledgeBase = knowledgeBase;
        this.metrics = metrics;
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
        if (answer == null || answer.isBlank()) {
            answer = analyzeService.analyze(jobDescription).getSummary();
        }
        return ResponseEntity.ok(analysisEvaluator.evaluateWithTexts(jobDescription, request.getDocuments(), answer));
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

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
