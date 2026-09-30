package io.github.aiaugmentedemployertoolkit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiaugmentedemployertoolkit.config.SystemPrompt;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import io.github.aiaugmentedemployertoolkit.dto.DataGuard;
import io.github.aiaugmentedemployertoolkit.dto.TaskBreakdown;
import io.github.aiaugmentedemployertoolkit.dto.TransitionPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 岗位自动化分析核心业务。
 * <p>
 * 相比改造前的变化：
 * 1. 格式指令只在 {@link #buildSystemPrompt} 一处注入，不再同时出现在系统提示词、
 *    {@code BeanOutputConverter} 和用户输入三处；
 * 2. 流式输出不再把 JSON 源码推给前端，正文与结构化结果分离（见 {@link ResultSplitter}）；
 * 3. 会话记忆只写入自然语言正文，不把上一轮的 JSON 塞回上下文；
 * 4. 补齐校验、限流、超时、重试与 token 统计。
 */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    /** 流式结果事件前缀，前端据此识别结构化结果 */
    public static final String RESULT_EVENT_PREFIX = "[[RESULT]]";

    private static final String SYNC_LABEL = "analyze";
    private static final String STREAM_LABEL = "analyze-stream";
    private static final String RATE_LIMIT_KEY_SYNC = "analyze:sync";
    private static final String RATE_LIMIT_KEY_STREAM = "analyze:stream";

    private static final String DEFAULT_DISCLAIMER =
            "该比率指「可自动化任务」占全部任务的比重，不等于人员缩减比例；可增强任务才是人机协作的主要机会。";

    private final ChatClient chatClient;
    private final ConversationMemory memory;
    private final InputValidator validator;
    private final TokenBucketRateLimiter rateLimiter;
    private final LlmUsageMetrics metrics;
    private final KnowledgeBaseService knowledgeBase;
    private final ObjectMapper objectMapper;
    private final BeanOutputConverter<AnalyzeResponse> outputConverter;
    private final String baseSystemPrompt;
    private final StanceGuard stanceGuard = new StanceGuard();

    private final long timeoutSeconds;
    private final long streamTimeoutSeconds;
    private final int maxRetries;

    public AnalyzeService(ChatClient chatClient,
                          SystemPrompt systemPrompt,
                          ConversationMemory memory,
                          InputValidator validator,
                          TokenBucketRateLimiter rateLimiter,
                          LlmUsageMetrics metrics,
                          KnowledgeBaseService knowledgeBase,
                          @Value("${app.analysis.timeout-seconds:120}") long timeoutSeconds,
                          @Value("${app.analysis.stream-timeout-seconds:180}") long streamTimeoutSeconds,
                          @Value("${app.analysis.max-retries:2}") int maxRetries) {
        this.chatClient = chatClient;
        this.baseSystemPrompt = systemPrompt.text();
        this.memory = memory;
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.knowledgeBase = knowledgeBase;
        this.objectMapper = new ObjectMapper();
        this.outputConverter = new BeanOutputConverter<>(AnalyzeResponse.class);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.streamTimeoutSeconds = Math.max(1, streamTimeoutSeconds);
        this.maxRetries = Math.max(0, maxRetries);
    }

    /**
     * 同步分析：校验 → 限流 → 调用 → 结构化解析。
     */
    public AnalyzeResponse analyze(String jobDescription) {
        String input = validator.validate(jobDescription);
        if (!rateLimiter.tryAcquire(RATE_LIMIT_KEY_SYNC)) {
            throw new RateLimitExceededException("分析请求过于频繁，请稍后再试");
        }

        try {
            AnalyzeResponse response = Mono.fromCallable(() -> callOnce(input))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(400))
                            .maxBackoff(Duration.ofSeconds(4))
                            .filter(e -> !(e instanceof IllegalArgumentException)))
                    .block();
            applyGuard(response);
            return response;
        } catch (RuntimeException e) {
            metrics.recordError(SYNC_LABEL);
            throw new IllegalStateException("AI 分析失败：" + rootMessage(e), e);
        }
    }

    /**
     * 流式分析（SSE），支持多轮追问。
     * <p>
     * 首次分析（无历史）时系统会追加结构化输出要求；追问时不追加，返回自由文本。
     * 输出由两类事件组成：普通正文片段，以及最后一条以 {@link #RESULT_EVENT_PREFIX} 开头的结构化结果。
     */
    public Flux<String> analyzeStream(String sessionId, String userText) {
        final String input;
        try {
            input = validator.validate(userText);
        } catch (IllegalArgumentException e) {
            return Flux.error(e);
        }
        if (!rateLimiter.tryAcquire(RATE_LIMIT_KEY_STREAM)) {
            return Flux.error(new RateLimitExceededException("分析请求过于频繁，请稍后再试"));
        }

        List<Message> history = memory.history(sessionId);
        boolean firstTurn = history.isEmpty();

        ResultSplitter splitter = new ResultSplitter();
        AtomicReference<ChatResponse> lastResponseWithUsage = new AtomicReference<>();

        Flux<String> body = chatClient.prompt()
                .system(buildSystemPrompt(firstTurn, true))
                .messages(history)
                .user(validator.wrapAsUntrusted(input))
                .stream()
                .chatResponse()
                .timeout(Duration.ofSeconds(streamTimeoutSeconds))
                .doOnNext(response -> {
                    if (response != null && response.getMetadata() != null
                            && response.getMetadata().getUsage() != null) {
                        lastResponseWithUsage.set(response);
                    }
                })
                .map(AnalyzeService::textOf)
                .filter(text -> !text.isEmpty())
                .map(splitter::accept)
                .map(this::guardVisiblePiece)
                .filter(piece -> !piece.isEmpty());

        Flux<String> tail = Flux.defer(() -> {
            metrics.record(STREAM_LABEL, lastResponseWithUsage.get());
            String visibleText = splitter.getVisibleText();
            memory.append(sessionId, input, visibleText);

            // 立场护栏：对整段可见正文做一次检测，命中即告警（正文已流式下发，无法回改，仅告警）
            if (stanceGuard.detectsReplacementFraming(visibleText)) {
                log.warn("立场护栏触发（流式正文）：输出出现替代/裁员式表述 {}",
                        stanceGuard.matchedPhrases(visibleText));
            }

            String rest = guardText(splitter.drainVisible());

            String rawResult = splitter.drainResult();
            String json = compactJson(extractJson(rawResult));
            AnalyzeResponse resultResponse = null;
            if (!json.isEmpty()) {
                resultResponse = parseResponse(rawResult);
                if (resultResponse != null) {
                    applyGuard(resultResponse);
                    try {
                        json = compactJson(objectMapper.writeValueAsString(resultResponse));
                    } catch (Exception e) {
                        log.warn("转岗结果序列化失败，回退为原始 JSON：{}", e.getMessage());
                    }
                }
            }
            if (json.isEmpty()) {
                log.warn("流式输出未包含结构化结果（模型未遵守分隔标记），本次只返回正文");
                return rest.isEmpty() ? Flux.<String>empty() : Flux.just(rest);
            }
            String resultEvent = RESULT_EVENT_PREFIX + json;
            return rest.isEmpty() ? Flux.just(resultEvent) : Flux.just(rest, resultEvent);
        });

        return body.concatWith(tail)
                .onErrorResume(error -> {
                    metrics.recordError(STREAM_LABEL);
                    return Flux.error(new IllegalStateException("AI 分析失败：" + rootMessage(error), error));
                });
    }

    /**
     * 构建系统提示词。
     * <p>
     * 结构化输出的格式指令只在这里注入一次：需要 JSON 时追加 {@code BeanOutputConverter} 生成的 schema，
     * 追问时不追加，避免格式指令污染对话。
     */
    String buildSystemPrompt(boolean structured, boolean streaming) {
        if (!structured) {
            return baseSystemPrompt;
        }
        StringBuilder sb = new StringBuilder(baseSystemPrompt)
                .append("\n\n## 输出格式\n")
                .append(outputConverter.getFormat());

        if (streaming) {
            sb.append("\n\n## 流式输出约定\n")
                    .append("你的回答会被流式展示给用户，请按以下顺序输出：\n")
                    .append("1. 先输出面向人的自然语言分析正文，可以使用 Markdown；\n")
                    .append("2. 正文结束后，在单独一行输出标记 ")
                    .append(ResultSplitter.PRIMARY_MARKER)
                    .append("；\n")
                    .append("3. 标记之后输出严格符合上述格式的 JSON，不要再用代码块包裹。\n");
        } else {
            sb.append("\n\n请只输出 JSON，不要输出任何其他文字。\n");
        }
        return sb.toString();
    }

    private AnalyzeResponse callOnce(String input) {
        ChatResponse response = chatClient.prompt()
                .system(buildSystemPrompt(true, false))
                .user(validator.wrapAsUntrusted(input))
                .call()
                .chatResponse();
        metrics.record(SYNC_LABEL, response);
        return parseResponse(textOf(response));
    }

    /**
     * 解析 LLM 返回文本：优先 {@code BeanOutputConverter}，失败回退手动解析，再失败回退纯文本。
     */
    AnalyzeResponse parseResponse(String rawResponse) {
        try {
            return outputConverter.convert(rawResponse);
        } catch (Exception e) {
            log.debug("BeanOutputConverter 解析失败，尝试手动 JSON 解析: {}", e.getMessage());
        }
        return parseJsonManually(rawResponse);
    }

    private AnalyzeResponse parseJsonManually(String rawResponse) {
        try {
            String json = extractJson(rawResponse);
            JsonNode node = objectMapper.readTree(json);

            double ratio = Math.max(0.0, Math.min(1.0, node.path("automationRatio").asDouble(0.5)));
            String summary = node.path("summary").asText("无法解析分析结果");

            return new AnalyzeResponse(
                    ratio,
                    summary,
                    parseTaskBreakdown(node.path("taskBreakdown")),
                    node.path("ratioBasis").asText(""),
                    node.path("ratioDisclaimer").asText(DEFAULT_DISCLAIMER),
                    parseTransitionPath(node.path("transitionPath")),
                    null);
        } catch (Exception e) {
            log.warn("解析 LLM 返回 JSON 失败，使用原始文本作为 summary: {}", e.getMessage());
            return new AnalyzeResponse(
                    0.5,
                    rawResponse,
                    null,
                    "",
                    DEFAULT_DISCLAIMER,
                    new TransitionPath(
                            List.of("请重新分析以获取转岗建议"),
                            "待评估",
                            "待评估",
                            "每个岗位都有可迁移的核心价值，转岗不是从零开始。"),
                    null);
        }
    }

    private TaskBreakdown parseTaskBreakdown(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return new TaskBreakdown(
                toStringList(node.path("automatable")),
                toStringList(node.path("augmentable")),
                toStringList(node.path("humanOnly")));
    }

    private TransitionPath parseTransitionPath(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new TransitionPath(
                    List.of("请重新分析以获取转岗建议"),
                    "待评估",
                    "待评估",
                    "每个岗位都有可迁移的核心价值，转岗不是从零开始。");
        }

        List<String> skills = toStringList(node.path("transferableSkills"));

        return new TransitionPath(
                skills.isEmpty() ? List.of("待补充") : skills,
                node.path("suggestedRole").asText("待评估"),
                node.path("skillGap").asText("待评估"),
                node.path("encouragement").asText("你的经验是宝贵的资产，转岗是在此之上叠加新能力。"));
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            });
        }
        return values;
    }

    /**
     * 去掉模型可能添加的 Markdown 代码围栏。
     */
    static String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
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

    /**
     * 压缩为单行 JSON。SSE 按行传输，多行 JSON 会被拆成多个 data 事件，前端难以还原。
     */
    String compactJson(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.readTree(json).toString();
        } catch (Exception e) {
            log.debug("JSON 无法规范化，退化为删除换行: {}", e.getMessage());
            return json.replace("\r", "").replace("\n", " ");
        }
    }

    /**
     * 对单行流式可见片段做示意数据脱敏（不影响打字机效果，仅替换命中的数值片段）。
     */
    private String guardVisiblePiece(String piece) {
        return guardText(piece);
    }

    /**
     * 示意数据硬护栏：对一段文本脱敏。无护栏（知识库尚未加载/无 illustrative 文档）时原样返回。
     */
    private String guardText(String text) {
        IllustrativeDataGuard guard = knowledgeBase == null ? null : knowledgeBase.getGuard();
        if (guard == null || text == null) {
            return text == null ? "" : text;
        }
        return guard.check(text).sanitized();
    }

    /**
     * 对解析后的结构化结果施加两道输出层护栏，并写入 {@link DataGuard} 检测结果：
     * 1. 示意数据硬护栏——脱敏 summary / skillGap / encouragement 中的 illustrative 数值；
     * 2. 立场护栏——检测转岗建议是否出现「被替代 / 裁员」式立场失当表述。
     */
    void applyGuard(AnalyzeResponse response) {
        if (response == null) {
            return;
        }
        IllustrativeDataGuard ig = knowledgeBase == null ? null : knowledgeBase.getGuard();
        boolean leak = false;
        List<String> leaked = new ArrayList<>();

        if (ig != null) {
            if (response.getSummary() != null) {
                IllustrativeDataGuard.Result r = ig.check(response.getSummary());
                if (r.leakDetected()) {
                    leak = true;
                    leaked.addAll(r.leakedFragments());
                    response.setSummary(r.sanitized());
                }
            }
            TransitionPath tp = response.getTransitionPath();
            if (tp != null) {
                if (tp.getSkillGap() != null) {
                    IllustrativeDataGuard.Result r = ig.check(tp.getSkillGap());
                    if (r.leakDetected()) {
                        leak = true;
                        leaked.addAll(r.leakedFragments());
                        tp.setSkillGap(r.sanitized());
                    }
                }
                if (tp.getEncouragement() != null) {
                    IllustrativeDataGuard.Result r = ig.check(tp.getEncouragement());
                    if (r.leakDetected()) {
                        leak = true;
                        leaked.addAll(r.leakedFragments());
                        tp.setEncouragement(r.sanitized());
                    }
                }
                // suggestedRole / transferableSkills 是岗位名称或能力名，不含示意数值，跳过
            }
        }

        StringBuilder stanceText = new StringBuilder();
        if (response.getSummary() != null) {
            stanceText.append(response.getSummary()).append("\n");
        }
        TransitionPath tp = response.getTransitionPath();
        if (tp != null) {
            if (tp.getSuggestedRole() != null) stanceText.append(tp.getSuggestedRole()).append("\n");
            if (tp.getSkillGap() != null) stanceText.append(tp.getSkillGap()).append("\n");
            if (tp.getEncouragement() != null) stanceText.append(tp.getEncouragement());
        }
        List<String> stance = stanceGuard.matchedPhrases(stanceText.toString());
        boolean stanceHit = !stance.isEmpty();
        if (stanceHit) {
            log.warn("立场护栏触发：转岗建议出现替代/裁员式表述 {}", stance);
        }
        if (leak) {
            log.warn("示意数据硬护栏触发：输出泄露 illustrative 数值 {}，已脱敏", leaked);
        }

        response.setDataGuard(new DataGuard(leak, leaked, stanceHit, stance));
    }

    static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
