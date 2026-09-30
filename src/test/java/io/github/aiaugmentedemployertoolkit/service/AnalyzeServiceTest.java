package io.github.aiaugmentedemployertoolkit.service;

import io.github.aiaugmentedemployertoolkit.config.SystemPrompt;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.service.IllustrativeDataGuard;
import io.github.aiaugmentedemployertoolkit.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyzeServiceTest {

    private static final String BASE_PROMPT = "你是岗位分析师。";

    private static final String RESULT_JSON = "{\"automationRatio\":0.6,\"summary\":\"摘要\","
            + "\"taskBreakdown\":{\"automatable\":[\"订单录入\"],\"augmentable\":[\"数据核对\"],"
            + "\"humanOnly\":[\"客户沟通\"]},"
            + "\"ratioBasis\":\"5 项任务中 3 项可自动化\","
            + "\"ratioDisclaimer\":\"这是任务占比，不是裁员比例\","
            + "\"transitionPath\":{\"transferableSkills\":[\"客户沟通\"],"
            + "\"suggestedRole\":\"客户关系管理专员\","
            + "\"skillGap\":\"需要学习 CRM 系统\","
            + "\"encouragement\":\"你不是从零开始\"}}";

    private final List<Prompt> capturedPrompts = new ArrayList<>();

    // ---------- 同步分析 ----------

    @Test
    void analyzeParsesStructuredResponse() {
        AnalyzeService service = defaultService(RESULT_JSON, List.of());

        AnalyzeResponse response = service.analyze("负责每日订单录入、发票开具和客户电话回访");

        assertEquals(0.6, response.getAutomationRatio(), 0.0001);
        assertEquals("摘要", response.getSummary());
        assertNotNull(response.getTaskBreakdown());
        assertEquals(List.of("订单录入"), response.getTaskBreakdown().getAutomatable());
        assertEquals(List.of("数据核对"), response.getTaskBreakdown().getAugmentable());
        assertEquals(List.of("客户沟通"), response.getTaskBreakdown().getHumanOnly());
        assertEquals("5 项任务中 3 项可自动化", response.getRatioBasis());
        assertTrue(response.getRatioDisclaimer().contains("裁员比例"));
        assertEquals("客户关系管理专员", response.getTransitionPath().getSuggestedRole());
    }

    @Test
    void analyzeFallsBackToRawTextWhenModelReturnsProse() {
        AnalyzeService service = defaultService("这不是 JSON，只是一段自然语言。", List.of());

        AnalyzeResponse response = service.analyze("订单录入");

        assertEquals(0.5, response.getAutomationRatio(), 0.0001);
        assertTrue(response.getSummary().contains("这不是 JSON"));
        assertNotNull(response.getTransitionPath());
        assertNotNull(response.getRatioDisclaimer(), "兜底时也必须给出防误读说明");
    }

    @Test
    void analyzeRejectsBlankInput() {
        AnalyzeService service = defaultService(RESULT_JSON, List.of());

        assertThrows(IllegalArgumentException.class, () -> service.analyze("   "));
    }

    @Test
    void analyzeRejectsOverlongInput() {
        AnalyzeService service = serviceWith(RESULT_JSON, List.of(),
                new InputValidator(20),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics());

        assertThrows(IllegalArgumentException.class, () -> service.analyze("岗".repeat(21)));
    }

    @Test
    void analyzeRejectsWhenRateLimited() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.1, 100);
        AnalyzeService service = serviceWith(RESULT_JSON, List.of(),
                new InputValidator(2000), limiter,
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics());

        assertNotNull(service.analyze("第一次"));
        assertThrows(RateLimitExceededException.class, () -> service.analyze("第二次"));
    }

    @Test
    void analyzeRecordsTokenUsage() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();
        AnalyzeService service = serviceWith(RESULT_JSON, List.of(),
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                metrics);

        service.analyze("订单录入");

        LlmUsageMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.callCount());
        assertEquals(100, snapshot.promptTokens());
        assertEquals(50, snapshot.completionTokens());
    }

    // ---------- 流式分析 ----------

    @Test
    void streamEmitsVisibleTextThenSeparateResultEvent() {
        AnalyzeService service = defaultService("", List.of("分析正文。", ResultSplitter.PRIMARY_MARKER, RESULT_JSON));

        List<String> events = service.analyzeStream("s1", "订单录入").collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("分析正文。", events.get(0));
        assertTrue(events.get(1).startsWith(AnalyzeService.RESULT_EVENT_PREFIX));

        String payload = events.get(1).substring(AnalyzeService.RESULT_EVENT_PREFIX.length());
        AnalyzeResponse parsed = service.parseResponse(payload);
        assertEquals(0.6, parsed.getAutomationRatio(), 0.0001);
        assertEquals("摘要", parsed.getSummary());
    }

    @Test
    void streamNeverLeaksJsonIntoVisibleOutput() {
        AnalyzeService service = defaultService("", List.of("分析正文。", ResultSplitter.PRIMARY_MARKER, RESULT_JSON));

        List<String> events = service.analyzeStream("s1", "订单录入").collectList().block();

        assertNotNull(events);
        assertTrue(events.stream()
                        .noneMatch(e -> !e.startsWith(AnalyzeService.RESULT_EVENT_PREFIX)
                                && e.contains("automationRatio")),
                "正文事件里不应出现 JSON 字段");
    }

    @Test
    void streamStoresOnlyNaturalLanguageInMemory() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100);
        AnalyzeService service = serviceWith("", List.of("分析正文。", ResultSplitter.PRIMARY_MARKER, RESULT_JSON),
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                memory,
                new LlmUsageMetrics());

        service.analyzeStream("s1", "订单录入").collectList().block();

        List<Message> history = memory.history("s1");
        assertEquals(2, history.size());
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());
        assertEquals("分析正文。", history.get(1).getText(), "记忆里不应写入 JSON");
    }

    @Test
    void firstTurnInjectsFormatInstructionsButFollowUpDoesNot() {
        AnalyzeService service = defaultService("", List.of("分析正文。", ResultSplitter.PRIMARY_MARKER, RESULT_JSON));

        service.analyzeStream("s1", "订单录入").collectList().block();
        service.analyzeStream("s1", "薪资预期如何？").collectList().block();

        String firstTurnSystem = systemTextOf(0);
        String followUpSystem = systemTextOf(1);

        assertTrue(firstTurnSystem.contains("automationRatio"), "首轮应注入结构化格式指令");
        assertTrue(firstTurnSystem.contains("流式输出约定"));
        assertTrue(!followUpSystem.contains("automationRatio"), "追问不应再注入格式指令");
    }

    @Test
    void streamDegradesGracefullyWhenModelIgnoresMarker() {
        AnalyzeService service = defaultService("", List.of("只有正文，没有结构化结果。"));

        List<String> events = service.analyzeStream("s1", "订单录入").collectList().block();

        assertNotNull(events);
        String joined = String.join("", events);
        assertEquals("只有正文，没有结构化结果。", joined);
        assertTrue(events.stream().noneMatch(e -> e.startsWith(AnalyzeService.RESULT_EVENT_PREFIX)));
    }

    @Test
    void streamRejectsBlankInput() {
        AnalyzeService service = defaultService("", List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.analyzeStream("s1", " ").collectList().block());
    }

    @Test
    void streamRejectsWhenRateLimited() {
        AnalyzeService service = serviceWith("", List.of("正文"),
                new InputValidator(2000),
                new TokenBucketRateLimiter(1, 0.1, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics(),
                mock(KnowledgeBaseService.class));

        assertNotNull(service.analyzeStream("s1", "第一次").collectList().block());
        assertThrows(RateLimitExceededException.class,
                () -> service.analyzeStream("s1", "第二次").collectList().block());
    }

    @Test
    void streamRecordsTokenUsageOnce() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();
        AnalyzeService service = serviceWith("", List.of("正文。", ResultSplitter.PRIMARY_MARKER, RESULT_JSON),
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                metrics,
                mock(KnowledgeBaseService.class));

        service.analyzeStream("s1", "订单录入").collectList().block();

        assertEquals(1, metrics.snapshot().callCount());
        assertEquals(100, metrics.snapshot().promptTokens());
    }

    // ---------- 提示词构建 ----------

    @Test
    void buildSystemPromptInjectsFormatOnlyWhenStructured() {
        AnalyzeService service = defaultService("", List.of());

        assertEquals(BASE_PROMPT, service.buildSystemPrompt(false, false));

        String streaming = service.buildSystemPrompt(true, true);
        assertTrue(streaming.contains("automationRatio"));
        assertTrue(streaming.contains(ResultSplitter.PRIMARY_MARKER));
        assertTrue(streaming.contains("流式输出约定"));

        String sync = service.buildSystemPrompt(true, false);
        assertTrue(sync.contains("只输出 JSON"));
        assertTrue(!sync.contains("流式输出约定"));
    }

    @Test
    void extractJsonStripsCodeFences() {
        assertEquals("{\"a\":1}", AnalyzeService.extractJson("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", AnalyzeService.extractJson("{\"a\":1}"));
        assertEquals("", AnalyzeService.extractJson(null));
    }

    @Test
    void compactJsonRemovesNewlines() throws Exception {
        AnalyzeService service = defaultService("", List.of());

        String compacted = service.compactJson("{\n  \"automationRatio\": 0.5\n}");

        assertTrue(!compacted.contains("\n"), "SSE 按行传输，结果必须是单行");
        assertTrue(compacted.contains("\"automationRatio\":0.5"));
    }

    // ---------- 辅助 ----------

    private AnalyzeService defaultService(String syncText, List<String> streamTokens) {
        return serviceWith(syncText, streamTokens,
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics(),
                mock(KnowledgeBaseService.class));
    }

    private AnalyzeService serviceWith(String syncText,
                                       List<String> streamTokens,
                                       InputValidator validator,
                                       TokenBucketRateLimiter limiter,
                                       ConversationMemory memory,
                                       LlmUsageMetrics metrics,
                                       KnowledgeBaseService knowledgeBase) {
        ChatModel model = new FakeChatModel(syncText, streamTokens);
        return new AnalyzeService(ChatClient.builder(model).build(),
                new SystemPrompt(BASE_PROMPT),
                memory,
                validator,
                limiter,
                metrics,
                knowledgeBase,
                10,
                10,
                0);
    }

    /**
     * 兼容旧测试用例的 6 参重载：默认注入一个不携带护栏的 KnowledgeBaseService（不触发硬拦截）。
     */
    private AnalyzeService serviceWith(String syncText,
                                       List<String> streamTokens,
                                       InputValidator validator,
                                       TokenBucketRateLimiter limiter,
                                       ConversationMemory memory,
                                       LlmUsageMetrics metrics) {
        return serviceWith(syncText, streamTokens, validator, limiter, memory, metrics, mock(KnowledgeBaseService.class));
    }

    // ---------- 输出层护栏 ----------

    @Test
    void redactsLeakedIllustrativeNumbersFromSummary() {
        // 模拟知识库里 illustrative 片段含「成功率 100%」「+42%~+88%」这类示意数值
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.getGuard()).thenReturn(
                new IllustrativeDataGuard(List.of("成功率 100%", "+42%~+88%")));

        String leakedJson = "{\"automationRatio\":0.6,\"summary\":\"据示意案例，转岗成功率 100%，薪资 +42%~+88%。\","
                + "\"taskBreakdown\":{\"automatable\":[\"订单录入\"],\"augmentable\":[\"数据核对\"],"
                + "\"humanOnly\":[\"客户沟通\"]},"
                + "\"ratioBasis\":\"5 项任务中 3 项可自动化\","
                + "\"ratioDisclaimer\":\"这是任务占比，不是裁员比例\","
                + "\"transitionPath\":{\"transferableSkills\":[\"客户沟通\"],"
                + "\"suggestedRole\":\"客户关系管理专员\","
                + "\"skillGap\":\"需要学习 CRM 系统\","
                + "\"encouragement\":\"你不是从零开始\"}}";

        AnalyzeService service = serviceWith(leakedJson, List.of(),
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics(),
                kb);

        AnalyzeResponse response = service.analyze("订单录入");

        assertTrue(response.getDataGuard() != null && response.getDataGuard().isIllustrativeLeakDetected(),
                "应检测到示意数据泄露");
        assertTrue(response.getSummary().contains(IllustrativeDataGuard.REDACTED),
                "泄露的示意数值应被脱敏");
        assertFalse(response.getSummary().contains("100%"), "原文里的 100% 不应再出现");
    }

    @Test
    void flagsReplacementFramingInTransitionPath() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.getGuard()).thenReturn(null); // 无 illustrative 护栏，仅验证立场护栏

        String framingJson = "{\"automationRatio\":0.9,\"summary\":\"该岗位可被完全自动化。\","
                + "\"taskBreakdown\":{\"automatable\":[\"订单录入\"],\"augmentable\":[\"数据核对\"],"
                + "\"humanOnly\":[\"客户沟通\"]},"
                + "\"ratioBasis\":\"5 项任务中 4 项可自动化\","
                + "\"ratioDisclaimer\":\"这是任务占比，不是裁员比例\","
                + "\"transitionPath\":{\"transferableSkills\":[\"客户沟通\"],"
                + "\"suggestedRole\":\"待评估\","
                + "\"skillGap\":\"你已被AI替代，无需转岗\","
                + "\"encouragement\":\"很遗憾\"}}";

        AnalyzeService service = serviceWith(framingJson, List.of(),
                new InputValidator(2000),
                new TokenBucketRateLimiter(100, 100, 100),
                new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100),
                new LlmUsageMetrics(),
                kb);

        AnalyzeResponse response = service.analyze("订单录入");

        assertTrue(response.getDataGuard() != null && response.getDataGuard().isReplacementFramingDetected(),
                "应检测到立场失当表述");
        assertFalse(response.getDataGuard().isIllustrativeLeakDetected(), "本例不应有示意数据泄露");
    }

    private String systemTextOf(int index) {
        SystemMessage systemMessage = capturedPrompts.get(index).getSystemMessage();
        return systemMessage == null ? "" : systemMessage.getText();
    }

    private static ChatResponse response(String text, boolean withUsage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(withUsage ? new DefaultUsage(100, 50) : null)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private final class FakeChatModel implements ChatModel {

        private final String syncText;
        private final List<String> streamTokens;

        private FakeChatModel(String syncText, List<String> streamTokens) {
            this.syncText = syncText;
            this.streamTokens = streamTokens;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            capturedPrompts.add(prompt);
            return response(syncText, true);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            capturedPrompts.add(prompt);
            List<ChatResponse> responses = new ArrayList<>();
            for (int i = 0; i < streamTokens.size(); i++) {
                boolean last = i == streamTokens.size() - 1;
                responses.add(response(streamTokens.get(i), last));
            }
            return Flux.fromIterable(responses);
        }
    }
}
