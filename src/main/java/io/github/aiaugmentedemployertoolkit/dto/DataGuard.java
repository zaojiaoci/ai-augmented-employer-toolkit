package io.github.aiaugmentedemployertoolkit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 服务端对模型输出施加的两道「输出层护栏」的检测结果。
 * <p>
 * 该字段由 {@code AnalyzeService} 在解析模型返回后、下发前注入，
 * 模型本身不会产出此字段（不在其 JSON schema 中）。
 * 它把「可信度分级（软约束）」与「立场要求（软约束）」补成可观测、可拦截的硬信号：
 * <ul>
 *   <li>{@code illustrativeLeakDetected} — 输出是否泄露了知识库中标记为
 *       {@code illustrative}（示意、不可用于决策）的数值，命中片段已就地脱敏；</li>
 *   <li>{@code replacementFramingDetected} — 输出是否出现「被替代 / 裁员」式立场失当表述。</li>
 * </ul>
 * 前端可据此展示警示，评测可据此作为硬性失败信号。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataGuard {

    /** 是否检测到示意数据（illustrative）泄露并已脱敏 */
    private boolean illustrativeLeakDetected;

    /** 被脱敏的示意数据片段（已归一化，便于复现与核对） */
    private List<String> redactedFragments;

    /** 是否检测到「被替代 / 裁员」式立场失当表述 */
    private boolean replacementFramingDetected;

    /** 命中的立场失当措辞 */
    private List<String> matchedStancePhrases;
}
