package io.github.aiaugmentedemployertoolkit.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出层「示意数据硬护栏」。
 * <p>
 * 可信度分级（{@code KnowledgeBaseService}）是软约束：它靠 front-matter + 提示词规则，
 * 本质是请求模型自觉。本项目以「负责任」为卖点，却没有任何一道硬拦截保证
 * {@code illustrative} 文档里的数字不会从输出里漏出来——一旦模型「叛变」，系统性约束就失效。
 * <p>
 * 这个类把软约束补成硬护栏：在知识库加载时，从所有 {@code illustrative} 片段里提取出具体的
 * 数值表述（百分比、区间、带 + 的涨幅、倍数），构建成一个「禁用片段集合」；
 * 模型产出分析后，对输出做确定性扫描，命中即脱敏并告警。
 * <p>
 * 设计为纯函数、无 Spring 依赖，便于离线单元测试。
 */
public class IllustrativeDataGuard {

    /** 脱敏占位符：被识别为示意数据的片段会被替换成它，而不是悄悄保留 */
    public static final String REDACTED = "[示意数据已隐藏]";

    // 只拦「带单位/带结构」的数值，避免把通用的整数（如“8”“100”）误伤成示意数据
    private static final Pattern PERCENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern RANGE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%?\\s*[~～-]\\s*\\d+(?:\\.\\d+)?\\s*%?");
    private static final Pattern PLUS_PERCENT = Pattern.compile("\\+\\s*\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern MULTIPLIER = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[倍xX]");

    private final Set<String> forbidden = new LinkedHashSet<>();

    public IllustrativeDataGuard(Collection<String> illustrativeTexts) {
        if (illustrativeTexts == null) {
            return;
        }
        for (String text : illustrativeTexts) {
            extractFrom(text);
        }
    }

    private void extractFrom(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        collect(PERCENT, text);
        collect(RANGE, text);
        collect(PLUS_PERCENT, text);
        collect(MULTIPLIER, text);
    }

    private void collect(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String fragment = normalize(matcher.group());
            if (!fragment.isEmpty()) {
                forbidden.add(fragment);
            }
        }
    }

    private static String normalize(String raw) {
        // 去掉 % 与数字之间的空格、统一大小写，保证匹配稳定
        return raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 扫描一段文本，返回检测结果与脱敏后的文本。
     * <p>
     * 匹配忽略数字与中文之间的空格差异（模型可能输出「成功率 100%」也可能输出「成功率100%」），
     * 因此用「宽松正则」做替换：每个字符之间允许插入空白。
     */
    public Result check(String text) {
        if (text == null || text.isEmpty() || forbidden.isEmpty()) {
            return new Result(false, List.of(), text == null ? "" : text);
        }
        String normText = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String sanitized = text;
        List<String> leaked = new ArrayList<>();
        // 长片段优先：先替换区间/带 + 的，再替换短的百分比/倍数，避免子串先替换导致长片段漏匹配
        List<String> ordered = new ArrayList<>(forbidden);
        ordered.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String fragment : ordered) {
            String normFrag = fragment.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (normText.contains(normFrag)) {
                leaked.add(fragment);
                sanitized = sanitized.replaceAll(toLooseRegex(fragment), Matcher.quoteReplacement(REDACTED));
            }
        }
        return new Result(!leaked.isEmpty(), leaked, sanitized);
    }

    /**
     * 把片段转成「字符间允许空白」的宽松正则，便于忽略输出与知识库之间的空格差异。
     */
    private static String toLooseRegex(String fragment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fragment.length(); i++) {
            sb.append(Pattern.quote(String.valueOf(fragment.charAt(i)))).append("\\s*");
        }
        return sb.toString();
    }

    public record Result(boolean leakDetected, List<String> leakedFragments, String sanitized) {
    }
}
