package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmUsageMetricsTest {

    @Test
    void accumulatesTokenUsage() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();

        metrics.record("analyze", responseWithUsage(100, 50));
        metrics.record("analyze", responseWithUsage(200, 80));

        LlmUsageMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.callCount());
        assertEquals(300, snapshot.promptTokens());
        assertEquals(130, snapshot.completionTokens());
        assertEquals(430, snapshot.totalTokens());
        assertEquals(0, snapshot.errorCount());
    }

    @Test
    void countsCallsWhenModelReturnsNoUsage() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();

        metrics.record("analyze", new ChatResponse(List.of(new Generation(new AssistantMessage("hi")))));

        LlmUsageMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.callCount());
        assertEquals(0, snapshot.promptTokens());
        assertEquals(0, snapshot.totalTokens());
    }

    @Test
    void countsErrors() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();

        metrics.recordError("analyze");
        metrics.recordError("analyze-stream");

        assertEquals(2, metrics.snapshot().errorCount());
        assertEquals(0, metrics.snapshot().callCount());
    }

    @Test
    void toleratesNullResponse() {
        LlmUsageMetrics metrics = new LlmUsageMetrics();

        metrics.record("analyze", null);

        assertEquals(1, metrics.snapshot().callCount());
    }

    private static ChatResponse responseWithUsage(int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }
}
