package io.github.aiaugmentedemployertoolkit.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模拟薪资查询工具。
 * <p>
 * 当用户询问转岗后的薪资预期、收入对比时，LLM 会自动调用此工具
 * 获取具体薪资数据，而不是凭经验估算。
 * <p>
 * 生产环境可替换为对接真实招聘 API（如猎聘、BOSS 直聘等）。
 */
@Component
public class SalaryTool {

    /**
     * 模拟薪资数据库：职业 → 城市等级 → [最低年薪, 最高年薪]（单位：千元）
     */
    private static final Map<String, Map<String, int[]>> SALARY_DB = Map.ofEntries(
            Map.entry("数据分析师", Map.of(
                    "一线城市", new int[]{180, 300},
                    "二线城市", new int[]{120, 220},
                    "三线城市", new int[]{80, 160}
            )),
            Map.entry("Python开发工程师", Map.of(
                    "一线城市", new int[]{200, 380},
                    "二线城市", new int[]{140, 260},
                    "三线城市", new int[]{90, 180}
            )),
            Map.entry("项目经理", Map.of(
                    "一线城市", new int[]{220, 400},
                    "二线城市", new int[]{150, 280},
                    "三线城市", new int[]{100, 200}
            )),
            Map.entry("客户成功经理", Map.of(
                    "一线城市", new int[]{160, 280},
                    "二线城市", new int[]{110, 200},
                    "三线城市", new int[]{70, 140}
            )),
            Map.entry("自动化运维工程师", Map.of(
                    "一线城市", new int[]{190, 350},
                    "二线城市", new int[]{130, 240},
                    "三线城市", new int[]{85, 170}
            )),
            Map.entry("商业分析师", Map.of(
                    "一线城市", new int[]{200, 360},
                    "二线城市", new int[]{140, 250},
                    "三线城市", new int[]{90, 175}
            )),
            Map.entry("产品经理", Map.of(
                    "一线城市", new int[]{210, 400},
                    "二线城市", new int[]{145, 270},
                    "三线城市", new int[]{95, 190}
            )),
            Map.entry("人力资源数字化专员", Map.of(
                    "一线城市", new int[]{150, 260},
                    "二线城市", new int[]{100, 190},
                    "三线城市", new int[]{65, 130}
            ))
    );

    @Tool(description = "查询指定职业在不同城市等级的薪资范围。当用户询问转岗后薪资预期、待遇对比时调用此工具。")
    public String getSalaryRange(
            @ToolParam(description = "职业名称，例如：数据分析师、Python开发工程师、项目经理") String roleName,
            @ToolParam(description = "城市等级：一线城市、二线城市 或 三线城市。不指定则返回所有城市等级数据", required = false) String cityTier) {

        Map<String, int[]> tiers = SALARY_DB.get(roleName);
        if (tiers == null) {
            return "未找到「" + roleName + "」的薪资数据。当前支持查询的职业有："
                    + String.join("、", SALARY_DB.keySet());
        }

        // 指定了城市等级
        if (cityTier != null && tiers.containsKey(cityTier)) {
            int[] range = tiers.get(cityTier);
            return roleName + " 在" + cityTier + "的年薪范围："
                    + range[0] + "K - " + range[1] + "K（千元），"
                    + "即约 " + (range[0] / 10) + "万 - " + (range[1] / 10) + "万元/年";
        }

        // 返回所有城市等级
        StringBuilder sb = new StringBuilder(roleName + " 薪资范围（年薪，千元）：\n");
        tiers.forEach((tier, range) ->
                sb.append("  ").append(tier).append("：")
                        .append(range[0]).append("K - ").append(range[1]).append("K")
                        .append("（约 ").append(range[0] / 10).append("万 - ")
                        .append(range[1] / 10).append("万）\n")
        );
        return sb.toString().trim();
    }

}
