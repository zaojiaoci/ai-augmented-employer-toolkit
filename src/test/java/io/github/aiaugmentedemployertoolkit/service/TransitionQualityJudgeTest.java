package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转岗建议质量评委的离线测试：用假模型扮演「评委 LLM」，验证分数与反馈能正确解析，
 * 以及空文本 / 不可解析输出的降级行为。
 */
class TransitionQualityJudgeTest {

    @Test
    void parsesScoreAndFeedback() {
        TransitionQualityJudge judge = new TransitionQualityJudge(
                new FixedModel("{\"score\": 0.8, \"feedback\": \"建议岗位与技能匹配，缺口具体\"}"));

        TransitionQualityJudge.Result result =
                judge.judge("订单录入岗", "建议转岗方向：CRM专员\n能力缺口：学习 CRM 系统", "可自动化：订单录入");

        assertEquals(0.8f, result.score(), 0.0001);
        assertEquals("建议岗位与技能匹配，缺口具体", result.feedback());
    }

    @Test
    void clampsScoreToRange() {
        TransitionQualityJudge judge = new TransitionQualityJudge(
                new FixedModel("{\"score\": 1.7, \"feedback\": \"超出范围\"}"));

        assertEquals(1.0f, judge.judge("x", "y", "z").score(), 0.0001);
    }

    @Test
    void skipsWhenTransitionBlank() {
        TransitionQualityJudge judge = new TransitionQualityJudge(new FixedModel("{\"score\":0.0}"));

        TransitionQualityJudge.Result result = judge.judge("岗位", "  ", "任务");
        assertEquals(0.5f, result.score(), 0.0001);
        assertTrue(result.feedback().contains("跳过"));
    }

    @Test
    void degradesOnUnparsableOutput() {
        TransitionQualityJudge judge = new TransitionQualityJudge(new FixedModel("不是 JSON"));

        TransitionQualityJudge.Result result = judge.judge("岗位", "转岗建议文本", "任务");
        assertEquals(0.5f, result.score(), 0.0001);
        assertTrue(result.feedback().contains("未评分"));
    }

    private static final class FixedModel implements ChatModel {
        private final String text;

        private FixedModel(String text) {
            this.text = text;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
