package io.github.aiaugmentedemployertoolkit.controller;

import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.TaskBreakdown;
import io.github.aiaugmentedemployertoolkit.dto.TransitionPath;
import io.github.aiaugmentedemployertoolkit.service.AnalysisEvaluator;
import io.github.aiaugmentedemployertoolkit.service.AnalyzeService;
import io.github.aiaugmentedemployertoolkit.service.KnowledgeBaseService;
import io.github.aiaugmentedemployertoolkit.service.LlmUsageMetrics;
import io.github.aiaugmentedemployertoolkit.service.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyzeControllerTest {

    private final AnalyzeService analyzeService = mock(AnalyzeService.class);
    private final AnalysisEvaluator analysisEvaluator = mock(AnalysisEvaluator.class);
    private final LlmUsageMetrics metrics = new LlmUsageMetrics();

    private final KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalyzeController(analyzeService, analysisEvaluator, knowledgeBase, metrics))
                .build();
    }

    @Test
    void returnsStructuredAnalysis() throws Exception {
        when(analyzeService.analyze(anyString())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"负责每日订单录入\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.automationRatio").value(0.6))
                .andExpect(jsonPath("$.summary").value("摘要"))
                .andExpect(jsonPath("$.taskBreakdown.automatable[0]").value("订单录入"))
                .andExpect(jsonPath("$.ratioDisclaimer").value("这是任务占比，不是裁员比例"));
    }

    @Test
    void mapsValidationFailureToBadRequest() throws Exception {
        when(analyzeService.analyze(anyString()))
                .thenThrow(new IllegalArgumentException("岗位描述不能为空"));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("岗位描述不能为空"));
    }

    @Test
    void mapsRateLimitToTooManyRequests() throws Exception {
        when(analyzeService.analyze(anyString()))
                .thenThrow(new RateLimitExceededException("分析请求过于频繁，请稍后再试"));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("分析请求过于频繁，请稍后再试"));
    }

    @Test
    void mapsUpstreamFailureToBadGateway() throws Exception {
        when(analyzeService.analyze(anyString()))
                .thenThrow(new IllegalStateException("AI 分析失败：连接超时"));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("AI 分析失败：连接超时"));
    }

    @Test
    void generatesSessionIdWhenNotProvided() throws Exception {
        when(analyzeService.analyzeStream(anyString(), anyString())).thenReturn(Flux.just("正文"));

        mockMvc.perform(post("/api/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        verify(analyzeService).analyzeStream(sessionId.capture(), eq("订单录入"));
        assertNotNull(sessionId.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(sessionId.getValue().isBlank());
    }

    @Test
    void reusesProvidedSessionId() throws Exception {
        when(analyzeService.analyzeStream(anyString(), anyString())).thenReturn(Flux.just("正文"));

        mockMvc.perform(post("/api/analyze/stream")
                        .param("sessionId", "session-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"追问\"}"))
                .andExpect(status().isOk());

        verify(analyzeService).analyzeStream("session-001", "追问");
    }

    @Test
    void exposesUsageSnapshot() throws Exception {
        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(0))
                .andExpect(jsonPath("$.totalTokens").value(0))
                .andExpect(jsonPath("$.errorCount").value(0));
    }

    @Test
    void exposesKnowledgeSourceCredibility() throws Exception {
        when(knowledgeBase.credibilityBySource()).thenReturn(
                java.util.Map.of("career-transition-cases.md", "reported",
                        "transition-examples.md", "illustrative"));

        mockMvc.perform(get("/api/knowledge/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['career-transition-cases.md']").value("reported"))
                .andExpect(jsonPath("$.['transition-examples.md']").value("illustrative"));
    }

    @Test
    void evaluatesProvidedAnswer() throws Exception {
        when(analysisEvaluator.isEnabled()).thenReturn(true);
        when(analysisEvaluator.evaluateWithTexts(eq("订单录入"), eq(List.of("上下文")), eq("分析内容")))
                .thenReturn(new AnalysisEvaluator.Result(0.9f, "相关", 0.8f, "忠实"));

        mockMvc.perform(post("/api/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\",\"answer\":\"分析内容\",\"documents\":[\"上下文\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relevancyScore").value(0.9))
                .andExpect(jsonPath("$.faithfulnessScore").value(0.8))
                .andExpect(jsonPath("$.relevancyFeedback").value("相关"));
    }

    @Test
    void runsAnalysisFirstWhenAnswerOmitted() throws Exception {
        when(analysisEvaluator.isEnabled()).thenReturn(true);
        when(analyzeService.analyze("订单录入")).thenReturn(sampleResponse());
        // documents 省略时为 null，anyList() 不匹配 null
        when(analysisEvaluator.evaluateWithTexts(eq("订单录入"), any(), eq("摘要")))
                .thenReturn(new AnalysisEvaluator.Result(0.7f, "ok", 0.6f, "ok"));

        mockMvc.perform(post("/api/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relevancyScore").value(0.7));

        verify(analyzeService).analyze("订单录入");
    }

    @Test
    void refusesEvaluationWhenDisabled() throws Exception {
        when(analysisEvaluator.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobDescription\":\"订单录入\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsEvaluationWithoutJobDescription() throws Exception {
        when(analysisEvaluator.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static AnalyzeResponse sampleResponse() {
        return new AnalyzeResponse(
                0.6,
                "摘要",
                new TaskBreakdown(List.of("订单录入"), List.of("数据核对"), List.of("客户沟通")),
                "5 项任务中 3 项可自动化",
                "这是任务占比，不是裁员比例",
                new TransitionPath(List.of("客户沟通"), "客户关系管理专员", "需要学习 CRM", "你不是从零开始"));
    }
}
