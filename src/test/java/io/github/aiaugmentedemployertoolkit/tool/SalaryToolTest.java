package io.github.aiaugmentedemployertoolkit.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalaryToolTest {

    private final SalaryTool tool = new SalaryTool();

    @Test
    void returnsRangeForKnownRoleAndCity() {
        String result = tool.getSalaryRange("数据分析师", "一线城市");

        assertTrue(result.contains("数据分析师"));
        assertTrue(result.contains("180K - 300K"), result);
        assertTrue(result.contains("18万"), result);
        assertTrue(result.contains("30万"), result);
        assertFalse(result.contains("18.0万"), "整数万元不应带小数位");
    }

    @Test
    void returnsAllCityTiersWhenCityOmitted() {
        String result = tool.getSalaryRange("数据分析师", null);

        assertTrue(result.contains("一线城市"));
        assertTrue(result.contains("二线城市"));
        assertTrue(result.contains("三线城市"));
    }

    @Test
    void fallsBackToAllTiersForUnknownCity() {
        String result = tool.getSalaryRange("数据分析师", "四线城市");

        assertTrue(result.contains("薪资范围"));
        assertTrue(result.contains("一线城市"));
    }

    @Test
    void tellsModelWhichRolesAreSupported() {
        String result = tool.getSalaryRange("不存在的岗位", null);

        assertTrue(result.contains("未找到"), result);
        assertTrue(result.contains("数据分析师"), "应提示支持的岗位列表");
    }

    @Test
    void convertsThousandYuanToWanWithoutLosingPrecision() {
        // 65K 曾被整数除法显示成 6 万
        assertTrue("6.5万".equals(SalaryTool.toWan(65)));
        assertTrue("18万".equals(SalaryTool.toWan(180)));
        assertTrue("13万".equals(SalaryTool.toWan(130)));
    }

    @Test
    void keepsHalfWanPrecisionInRangeOutput() {
        String result = tool.getSalaryRange("人力资源数字化专员", "三线城市");

        assertTrue(result.contains("6.5万"), result);
        assertTrue(result.contains("13万"), result);
    }
}
