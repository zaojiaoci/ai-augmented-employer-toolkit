package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 立场护栏的离线测试：验证「被替代 / 裁员」式立场失当表述能被识别，
 * 而中性的「任务可被自动化」描述不会被误判。
 */
class StanceGuardTest {

    private final StanceGuard guard = new StanceGuard();

    @Test
    void detectsReplacementFraming() {
        assertTrue(guard.detectsReplacementFraming("很遗憾，你已被AI替代，无法转岗。"));
        assertTrue(guard.detectsReplacementFraming("该岗位可被裁掉，建议另谋出路。"));
        assertTrue(guard.detectsReplacementFraming("这类员工应该被优化掉。"));
    }

    @Test
    void ignoresNeutralAutomationDescription() {
        assertFalse(guard.detectsReplacementFraming("该岗位约 60% 的任务可被自动化，仍需人工把关。"));
        assertFalse(guard.detectsReplacementFraming("AI 能显著提升这部分任务的效率。"));
        assertFalse(guard.detectsReplacementFraming("可增强任务才是人机协作的主要机会。"));
    }

    @Test
    void reportsMatchedPhrases() {
        assertTrue(guard.matchedPhrases("你被AI替代了").contains("被ai替代"));
        assertTrue(guard.matchedPhrases("建议裁掉你").contains("裁掉你"));
    }

    @Test
    void handlesBlankInput() {
        assertFalse(guard.detectsReplacementFraming(""));
        assertFalse(guard.detectsReplacementFraming(null));
    }
}
