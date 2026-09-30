package io.github.aiaugmentedemployertoolkit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 立场护栏：检测输出是否出现「替代 / 裁员 / 淘汰」式的立场失当表述。
 * <p>
 * 本项目的价值主张是「增强而非替代」。提示词里已经要求模型给出升级式而非重来式的转岗建议，
 * 但这同样是软约束。这里做一道确定性检测：一旦输出出现明确的「你被替代 / 可裁掉」式措辞，
 * 立刻告警（并可在评测中作为硬性失败信号），守住项目的价值观底线。
 * <p>
 * 设计为纯函数、无 Spring 依赖，便于离线单元测试与评测链路复用。
 */
public class StanceGuard {

    // 只匹配明确、强立场的措辞，避免把「这个任务可被自动化」这类中性描述误判
    private static final List<String> REPLACEMENT_PHRASES = List.of(
            "被ai替代", "被ai取代", "被人工智能替代", "被人工智能取代",
            "被机器替代", "被机器取代",
            "可以裁", "建议裁", "应该裁", "可以裁掉", "建议裁掉", "应该裁掉",
            "可裁掉", "可被裁掉", "裁掉你", "裁员",
            "被淘汰", "遭到淘汰", "优化掉", "被优化",
            "丢掉工作", "失去工作", "不再需要你", "不需要你了",
            "你被替代", "你被取代", "没有价值", "毫无价值", "可以被取代");

    /**
     * 是否检测到立场失当（替代 / 裁员式）表述。
     */
    public boolean detectsReplacementFraming(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return REPLACEMENT_PHRASES.stream().anyMatch(lowered::contains);
    }

    /**
     * 返回命中的具体措辞，便于日志与评测反馈。
     */
    public List<String> matchedPhrases(String text) {
        List<String> matched = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matched;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String phrase : REPLACEMENT_PHRASES) {
            if (lowered.contains(phrase)) {
                matched.add(phrase);
            }
        }
        return matched;
    }
}
