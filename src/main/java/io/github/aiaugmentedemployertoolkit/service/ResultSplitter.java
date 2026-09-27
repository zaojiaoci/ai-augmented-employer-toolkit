package io.github.aiaugmentedemployertoolkit.service;

/**
 * 流式输出拆分器：把「模型输出」拆成「给人看的自然语言正文」和「给前端用的结构化 JSON」。
 * <p>
 * 背景：首次分析既要流式打字机效果，又要产出结构化卡片。
 * 如果直接把模型输出推给前端，用户会看到一大段 JSON 源码滚屏。
 * 这里要求模型先输出自然语言正文，再用标记 {@link #PRIMARY_MARKER} 换行输出 JSON；
 * 拆分器在流式过程中识别标记，标记之前的内容照常推送，标记之后的内容转入缓冲区不再外发，
 * 流结束后由调用方作为单独一个事件发出。
 * <p>
 * 标记可能跨越多个 token，因此保留一个不超过最长标记长度的窗口暂不外发。
 */
public class ResultSplitter {

    /** 约定的结果分隔标记，需与提示词中的要求一致 */
    public static final String PRIMARY_MARKER = "<!--RESULT-->";

    private static final String[] MARKERS = {PRIMARY_MARKER, "```json"};

    private static final int MAX_WINDOW = maxMarkerLength();

    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder result = new StringBuilder();
    private String window = "";
    private boolean resultStarted;

    /**
     * 消费一个 token，返回「本次应当推送给前端的正文片段」，可能为 {@code null} 或空串。
     */
    public String accept(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (resultStarted) {
            result.append(token);
            return "";
        }
        String combined = window + token;
        int[] hit = findMarker(combined);
        if (hit != null) {
            resultStarted = true;
            visible.append(combined, 0, hit[0]);
            result.append(combined.substring(hit[0] + hit[1]));
            window = "";
            return combined.substring(0, hit[0]);
        }
        if (combined.length() > MAX_WINDOW) {
            int cut = combined.length() - MAX_WINDOW;
            visible.append(combined, 0, cut);
            window = combined.substring(cut);
            return combined.substring(0, cut);
        }
        window = combined;
        return "";
    }

    /**
     * 流结束时取出窗口内残留的正文（不足一个标记长度的部分）。
     */
    public String drainVisible() {
        if (resultStarted || window.isEmpty()) {
            return "";
        }
        String out = window;
        visible.append(window);
        window = "";
        return out;
    }

    /**
     * 流结束时取出结构化结果原文；模型未遵守约定时返回空串，调用方走兜底解析。
     */
    public String drainResult() {
        return resultStarted ? result.toString().trim() : "";
    }

    public boolean hasResult() {
        return resultStarted;
    }

    /**
     * 完整正文，用于写入会话记忆——只存自然语言，不把 JSON 塞回上下文。
     * 流式过程中调用也能拿到完整内容（含尚未外发的窗口部分）。
     */
    public String getVisibleText() {
        return window.isEmpty() ? visible.toString() : visible.toString() + window;
    }

    private static int[] findMarker(String text) {
        int bestIndex = -1;
        int bestLength = 0;
        for (String marker : MARKERS) {
            int idx = text.indexOf(marker);
            if (idx >= 0 && (bestIndex < 0 || idx < bestIndex)) {
                bestIndex = idx;
                bestLength = marker.length();
            }
        }
        return bestIndex < 0 ? null : new int[]{bestIndex, bestLength};
    }

    private static int maxMarkerLength() {
        int max = 0;
        for (String marker : MARKERS) {
            max = Math.max(max, marker.length());
        }
        return max;
    }
}
