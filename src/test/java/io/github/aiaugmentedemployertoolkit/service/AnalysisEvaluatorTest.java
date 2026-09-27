package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估链路的离线测试：用假模型扮演「评委 LLM」，验证分数与反馈能正确解析出来。
 */
class AnalysisEvaluatorTest {

    private static final String JOB = "负责每日订单录入、发票开具和客户电话回访";
    private static final String ANSWER = "该岗位 5 项任务中 3 项可自动化。";

    @Test
    void parsesRelevancyAndFaithfulnessScores() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(
                new StubJudgeModel("{\"score\": 0.8, \"feedback\": \"分析覆盖了岗位核心任务\"}"), true);

        AnalysisEvaluator.Result result = evaluator.evaluate(JOB, List.of(new Document("订单录入属于重复性任务")), ANSWER);

        assertEquals(0.8f, result.relevancyScore(), 0.0001);
        assertEquals(0.8f, result.faithfulnessScore(), 0.0001);
        assertEquals("分析覆盖了岗位核心任务", result.relevancyFeedback());
        assertTrue(result.pass());
        assertEquals(0.8f, result.averageScore(), 0.0001);
    }

    @Test
    void reflectsLowScoreAsNotPassing() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(
                new StubJudgeModel("{\"score\": 0.0, \"feedback\": \"回答与上下文无关\"}"), true);

        AnalysisEvaluator.Result result = evaluator.evaluateWithTexts(JOB, List.of("无关上下文"), ANSWER);

        assertFalse(result.pass());
        assertEquals(0.0f, result.averageScore(), 0.0001);
    }

    @Test
    void averagesDifferentScores() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(
                new StubJudgeModel("{\"score\": 1.0, \"feedback\": \"ok\"}",
                        "{\"score\": 0.0, \"feedback\": \"不忠实\"}"), true);

        AnalysisEvaluator.Result result = evaluator.evaluate(JOB, List.of(), ANSWER);

        assertEquals(1.0f, result.relevancyScore(), 0.0001);
        assertEquals(0.0f, result.faithfulnessScore(), 0.0001);
        assertEquals(0.5f, result.averageScore(), 0.0001);
        assertFalse(result.pass());
    }

    @Test
    void acceptsTextContextAndNullContext() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(
                new StubJudgeModel("{\"score\": 0.5, \"feedback\": \"ok\"}"), true);

        assertEquals(0.5f, evaluator.evaluateWithTexts(JOB, List.of("上下文"), ANSWER).relevancyScore(), 0.0001);
        assertEquals(0.5f, evaluator.evaluateWithTexts(JOB, null, ANSWER).relevancyScore(), 0.0001);
        assertEquals(0.5f, evaluator.evaluate(JOB, null, ANSWER).relevancyScore(), 0.0001);
    }

    @Test
    void refusesWhenDisabled() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(new StubJudgeModel("{}"), false);

        assertFalse(evaluator.isEnabled());
        assertThrows(IllegalStateException.class, () -> evaluator.evaluate(JOB, List.of(), ANSWER));
    }

    @Test
    void propagatesUnparsableJudgeOutput() {
        AnalysisEvaluator evaluator = new AnalysisEvaluator(
                new StubJudgeModel("这不是 JSON"), true);

        assertThrows(RuntimeException.class, () -> evaluator.evaluate(JOB, List.of(), ANSWER));
    }

    /**
     * 扮演评委 LLM：按调用顺序返回预设的评分 JSON。
     */
    private static final class StubJudgeModel implements ChatModel {

        private final List<String> responses = new ArrayList<>();
        private int index;

        private StubJudgeModel(String... responses) {
            for (String response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = responses.isEmpty() ? "{}" : responses.get(Math.min(index++, responses.size() - 1));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
