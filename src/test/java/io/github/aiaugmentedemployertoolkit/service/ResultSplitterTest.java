package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultSplitterTest {

    @Test
    void splitsAtMarkerAndBuffersJson() {
        ResultSplitter splitter = new ResultSplitter();
        StringBuilder visible = new StringBuilder();

        for (String token : List.of("这是", "一段正文。", ResultSplitter.PRIMARY_MARKER, "{\"automationRatio\":0.5}")) {
            visible.append(splitter.accept(token));
        }
        visible.append(splitter.drainVisible());

        assertEquals("这是一段正文。", visible.toString());
        assertTrue(splitter.hasResult());
        assertEquals("{\"automationRatio\":0.5}", splitter.drainResult());
    }

    @Test
    void handlesMarkerSplitAcrossTokens() {
        ResultSplitter splitter = new ResultSplitter();
        StringBuilder visible = new StringBuilder();

        visible.append(splitter.accept("正文"));
        visible.append(splitter.accept("<!--RES"));
        visible.append(splitter.accept("ULT-->"));
        visible.append(splitter.accept("{\"a\":1}"));
        visible.append(splitter.drainVisible());

        assertEquals("正文", visible.toString());
        assertEquals("{\"a\":1}", splitter.drainResult());
    }

    @Test
    void keepsEverythingVisibleWhenNoMarkerPresent() {
        ResultSplitter splitter = new ResultSplitter();

        String out = splitter.accept("纯文本回答，没有 JSON 结构");
        out += splitter.drainVisible();

        assertEquals("纯文本回答，没有 JSON 结构", out);
        assertFalse(splitter.hasResult());
        assertEquals("", splitter.drainResult());
    }

    @Test
    void supportsCodeFenceMarker() {
        ResultSplitter splitter = new ResultSplitter();

        StringBuilder visible = new StringBuilder();
        visible.append(splitter.accept("分析正文"));
        visible.append(splitter.accept("```json"));
        visible.append(splitter.accept("{\"automationRatio\":0.4}"));
        visible.append(splitter.drainVisible());

        assertEquals("分析正文", visible.toString());
        assertTrue(splitter.hasResult());
    }

    @Test
    void visibleTextExcludesResultPart() {
        ResultSplitter splitter = new ResultSplitter();

        splitter.accept("摘要内容。");
        splitter.accept(ResultSplitter.PRIMARY_MARKER);
        splitter.accept("{\"automationRatio\":0.9}");

        assertEquals("摘要内容。", splitter.getVisibleText());
    }

    @Test
    void ignoresNullAndEmptyTokens() {
        ResultSplitter splitter = new ResultSplitter();

        assertEquals("", splitter.accept(null));
        assertEquals("", splitter.accept(""));

        splitter.accept("正文");
        assertEquals("正文", splitter.getVisibleText());
    }
}
