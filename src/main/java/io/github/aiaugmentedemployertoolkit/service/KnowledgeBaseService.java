package io.github.aiaugmentedemployertoolkit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库加载服务，应用启动时自动执行。
 * <p>
 * 扫描 classpath:knowledge/ 目录下的 Markdown 和 PDF 文件，
 * 使用 Tika 解析、分块后嵌入向量存储。
 */
@Component
public class KnowledgeBaseService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final VectorStore vectorStore;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(80)
            .withMinChunkLengthToEmbed(20)
            .withMaxNumChunks(5000)
            .withKeepSeparator(true)
            .build();

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("开始加载知识库文档...");

        int mdCount = ingestResources("classpath:knowledge/*.md");
        int pdfCount = 0;
        try {
            pdfCount = ingestResources("classpath:knowledge/pdf/*.pdf");
        } catch (Exception e) {
            log.warn("跳过 PDF 目录加载（目录不存在或无法访问）: {}", e.getMessage());
        }

        log.info("知识库加载完成，Markdown 文档 {} 块，PDF 文档 {} 块", mdCount, pdfCount);
    }

    /**
     * 加载指定路径模式的文档，解析 → 分块 → 嵌入 → 入库
     *
     * @return 入库的文档块数量
     */
    private int ingestResources(String locationPattern) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(locationPattern);

        if (resources.length == 0) {
            log.debug("未找到匹配的文档: {}", locationPattern);
            return 0;
        }

        List<Document> allChunks = new ArrayList<>();

        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            log.debug("正在解析: {}", fileName);

            // 1. Tika 统一解析（Markdown + PDF）
            List<Document> documents = new TikaDocumentReader(resource).get();

            // 2. 分块
            List<Document> chunks = splitter.apply(documents);

            // 3. 添加来源元数据
            for (Document chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("source", fileName);
                allChunks.add(new Document(chunk.getText(), metadata));
            }
        }

        // 4. 嵌入 + 存储
        if (!allChunks.isEmpty()) {
            vectorStore.add(allChunks);
            log.info("已入库 {} 块（来源: {}）", allChunks.size(), locationPattern);
        }

        return allChunks.size();
    }

}
