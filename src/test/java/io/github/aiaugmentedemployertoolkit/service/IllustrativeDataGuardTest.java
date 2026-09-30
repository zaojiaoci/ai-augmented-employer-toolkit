package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出层「示意数据硬护栏」的离线测试：验证从 illustrative 片段提取的数值能正确识别并脱敏，
 * 且不会误伤正常文本。
 */
class IllustrativeDataGuardTest {

    private static final List<String> ILLUSTRATIVE = List.of(
            "转岗成功率 100%，薪资涨幅 +42%~+88%，约 2 倍于原岗位。");

    @Test
    void detectsAndRedactsPercentage() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(ILLUSTRATIVE);

        IllustrativeDataGuard.Result result = guard.check("该案例转岗成功率 100%，值得参考。");

        assertTrue(result.leakDetected());
        assertTrue(result.sanitized().contains(IllustrativeDataGuard.REDACTED));
        assertFalse(result.sanitized().contains("100%"));
        assertTrue(result.leakedFragments().contains("100%"));
    }

    @Test
    void detectsRangeWithPlusAndTilde() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(ILLUSTRATIVE);

        IllustrativeDataGuard.Result result = guard.check("薪资可提升 +42%~+88%。");

        assertTrue(result.leakDetected());
        assertFalse(result.sanitized().contains("42%"));
        assertFalse(result.sanitized().contains("88%"));
    }

    @Test
    void detectsMultiplier() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(ILLUSTRATIVE);

        IllustrativeDataGuard.Result result = guard.check("收入可达原岗位 2 倍。");

        assertTrue(result.leakDetected());
        assertFalse(result.sanitized().contains("2 倍"));
    }

    @Test
    void ignoresWhitespaceDifferences() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(ILLUSTRATIVE);

        IllustrativeDataGuard.Result result = guard.check("成功率100%也出现了");

        assertTrue(result.leakDetected());
        assertFalse(result.sanitized().contains("成功率100%"));
    }

    @Test
    void doesNotFlagCleanText() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(ILLUSTRATIVE);

        IllustrativeDataGuard.Result result = guard.check("该岗位约 60% 任务可自动化，建议转岗到客户关系岗。");

        assertFalse(result.leakDetected());
        assertEquals("该岗位约 60% 任务可自动化，建议转岗到客户关系岗。", result.sanitized());
    }

    @Test
    void emptyGuardDoesNothing() {
        IllustrativeDataGuard guard = new IllustrativeDataGuard(List.of());
        IllustrativeDataGuard.Result result = guard.check("转岗成功率 100%");

        assertFalse(result.leakDetected());
        assertEquals("转岗成功率 100%", result.sanitized());
    }
}
