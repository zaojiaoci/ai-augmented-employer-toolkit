package io.github.aiaugmentedemployertoolkit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用量与 token 消耗统计。
 * <p>
 * LLM 应用的可观测性里，调用次数和 token 消耗是必备项（成本直接由它决定）。
 * 这里做进程内累计，并暴露 {@code GET /api/usage}；生产环境可改为 Micrometer 埋点。
 */
@Component
public class LlmUsageMetrics {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageMetrics.class);

    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    /**
     * 记录一次调用。流式场景下应在流结束后只记录一次（使用最后一个带 usage 的响应）。
     */
    public void record(String label, ChatResponse response) {
        callCount.incrementAndGet();
        Usage usage = usageOf(response);
        if (usage == null) {
            log.info("LLM 调用 [{}] 完成（模型未返回 usage）", label);
            return;
        }
        long prompt = nullSafe(usage.getPromptTokens());
        long completion = nullSafe(usage.getCompletionTokens());
        promptTokens.addAndGet(prompt);
        completionTokens.addAndGet(completion);
        log.info("LLM 调用 [{}] 完成，prompt={} completion={} total={}",
                label, prompt, completion, prompt + completion);
    }

    public void recordError(String label) {
        errorCount.incrementAndGet();
        log.warn("LLM 调用 [{}] 失败", label);
    }

    public Snapshot snapshot() {
        return new Snapshot(callCount.get(),
                promptTokens.get(),
                completionTokens.get(),
                promptTokens.get() + completionTokens.get(),
                errorCount.get());
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static long nullSafe(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    public record Snapshot(long callCount, long promptTokens, long completionTokens, long totalTokens,
                           long errorCount) {
    }
}
