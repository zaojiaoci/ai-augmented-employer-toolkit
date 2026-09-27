package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings("unchecked")
class KnowledgeBaseServiceTest {

    @Test
    void ingestsMarkdownWithSourceMetadata() throws Exception {
        VectorStore store = mock(VectorStore.class);
        KnowledgeBaseService service = new KnowledgeBaseService(store,
                "classpath:test-knowledge/*.md",
                "classpath:test-knowledge/pdf/*.pdf");

        service.run(null);

        org.mockito.ArgumentCaptor<List<Document>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store, atLeastOnce()).add(captor.capture());

        List<Document> documents = captor.getValue();
        assertFalse(documents.isEmpty(), "应至少入库一个分块");
        assertTrue(documents.stream().allMatch(d -> d.getMetadata().containsKey("source")),
                "每个分块都要带来源元数据，供引用标注使用");
        assertTrue(documents.stream().anyMatch(d -> d.getText().contains("订单录入")));
    }

    @Test
    void parsesCredibilityFromFrontMatter() {
        assertEquals("illustrative",
                KnowledgeBaseService.parseCredibility("---\ncredibility: illustrative\n---\n\n正文"));
        assertEquals("reported",
                KnowledgeBaseService.parseCredibility("---\ncredibility: reported\n---\n\n正文"));
    }

    @Test
    void fallsBackToMostCautiousLevelWhenUnmarked() {
        // 未标注的内容宁可低估，不能被当成事实
        assertEquals("illustrative", KnowledgeBaseService.parseCredibility("没有 front-matter 的正文"));
        assertEquals("illustrative", KnowledgeBaseService.parseCredibility(""));
        assertEquals("illustrative", KnowledgeBaseService.parseCredibility(null));
    }

    @Test
    void recordsCredibilityPerSource() throws Exception {
        VectorStore store = mock(VectorStore.class);
        KnowledgeBaseService service = new KnowledgeBaseService(store,
                "classpath:test-knowledge/*.md",
                "classpath:test-knowledge/pdf/*.pdf");

        service.run(null);

        String credibility = service.credibilityBySource().get("sample.md");
        assertEquals("structural", credibility, "测试文档声明的可信度应被登记");
        assertFalse(service.credibilityBySource().isEmpty());
    }

    @Test
    void toleratesMissingDirectories() throws Exception {
        VectorStore store = mock(VectorStore.class);
        KnowledgeBaseService service = new KnowledgeBaseService(store,
                "classpath:no-such-knowledge/*.md",
                "classpath:no-such-knowledge/pdf/*.pdf");

        // 目录不存在时不应导致启动崩溃
        service.run(null);

        verify(store, never()).add(anyList());
        verifyNoInteractions(store);
    }
}
