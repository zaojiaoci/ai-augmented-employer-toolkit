package io.github.aiaugmentedemployertoolkit.controller;

import io.github.aiaugmentedemployertoolkit.dto.AnalyzeRequest;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.service.AnalyzeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    public AnalyzeController(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    /**
     * 同步分析端点（保留原有接口）
     */
    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        return analyzeService.analyze(request.getJobDescription());
    }

    /**
     * 流式分析端点（SSE），支持打字机效果和多轮追问
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeStream(
            @RequestBody AnalyzeRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        return analyzeService.analyzeStream(sid, request.getJobDescription());
    }

}
