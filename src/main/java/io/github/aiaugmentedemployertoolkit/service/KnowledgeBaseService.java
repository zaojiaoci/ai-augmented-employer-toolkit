package io.github.aiaugmentedemployertoolkit.service;

import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库加载服务，应用启动时自动执行。
 * <p>
 * 扫描 classpath:knowledge/ 目录下的 Markdown 和 PDF 文件，
 * 使用 Tika 解析、分块后嵌入向量存储。
 * <p>
 * 每份文档在文件头用 YAML front-matter 声明 {@code credibility}（可信度等级），
 * 加载时解析出来写入 Document 元数据，供提示词引用规范与前端渲染使用。
 */
@Component
public class KnowledgeBaseService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    static final String DEFAULT_MARKDOWN_PATTERN = "classpath:knowledge/*.md";
    static final String DEFAULT_PDF_PATTERN = "classpath:knowledge/pdf/*.pdf";
    static final int DEFAULT_CHUNK_SIZE = 600;

    /** 未声明可信度时的兜底等级——宁可低估，不要让未标注内容被当成事实 */
    static final String DEFAULT_CREDIBILITY = "illustrative";

    static final Pattern FRONT_MATTER_CREDIBILITY =
            Pattern.compile("(?s)^---\\s*\\R.*?\\bcredibility\\s*:\\s*([a-zA-Z_-]+).*?\\R---");

    /** 入库文档的可信度登记表：文件名 → 等级 */
    private final Map<String, String> credibilityBySource = new LinkedHashMap<>();

    /** 加载期收集到的 illustrative（示意、不可用于决策）片段原文，用于构建输出层硬护栏 */
    private final List<String> illustrativeChunks = new ArrayList<>();

    /** 输出层「示意数据硬护栏」，在 ingest 完成后构建；未加载完成前为 null */
    private IllustrativeDataGuard guard;

    private final VectorStore vectorStore;
    private final String markdownPattern;
    private final String pdfPattern;

    /**
     * 递归字符分块器（Spring AI Alibaba 提供）。
     * <p>
     * 相比 {@code TokenTextSplitter} 按 token 数硬切，它会依次尝试按「空行 → 换行 → 空格 → 字符」切分，
     * 尽量不把段落和 Markdown 表格从中间切断——知识库里大量内容是表格，切断后检索质量会明显下降。
     */
    private final TextSplitter splitter;

    @Autowired
    public KnowledgeBaseService(VectorStore vectorStore,
                                @Value("${app.rag.chunk-size:600}") int chunkSize) {
        this(vectorStore, chunkSize, DEFAULT_MARKDOWN_PATTERN, DEFAULT_PDF_PATTERN);
    }

    /**
     * 支持自定义路径模式，便于测试使用独立的测试文档目录。
     */
    public KnowledgeBaseService(VectorStore vectorStore, String markdownPattern, String pdfPattern) {
        this(vectorStore, DEFAULT_CHUNK_SIZE, markdownPattern, pdfPattern);
    }

    public KnowledgeBaseService(VectorStore vectorStore,
                                int chunkSize,
                                String markdownPattern,
                                String pdfPattern) {
        this.vectorStore = vectorStore;
        this.markdownPattern = markdownPattern;
        this.pdfPattern = pdfPattern;
        this.splitter = new RecursiveCharacterTextSplitter(Math.max(50, chunkSize));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("开始加载知识库文档...");

        int mdCount = 0;
        try {
            mdCount = ingestResources(markdownPattern);
        } catch (Exception e) {
            // 目录缺失不应导致启动崩溃，但这是主知识库，需要显式告警
            log.error("Markdown 知识库加载失败（目录不存在或无法访问）: {}", e.getMessage());
        }

        int pdfCount = 0;
        try {
            pdfCount = ingestResources(pdfPattern);
        } catch (Exception e) {
            // PDF 是可选补充，缺失属正常情况
            log.warn("跳过 PDF 目录加载（目录不存在或无法访问）: {}", e.getMessage());
        }

        log.info("知识库加载完成，Markdown 文档 {} 块，PDF 文档 {} 块，共登记 {} 个来源",
                mdCount, pdfCount, credibilityBySource.size());

        if (!illustrativeChunks.isEmpty()) {
            this.guard = new IllustrativeDataGuard(illustrativeChunks);
            log.info("已构建输出层示意数据护栏，扫描 {} 个 illustrative 片段", illustrativeChunks.size());
        } else {
            log.info("未发现 illustrative 文档，输出层示意数据护栏为空（不拦截）");
        }
    }

    /**
     * 输出层「示意数据硬护栏」。可能为 null（加载尚未完成，或启动期未成功加载）。
     */
    public IllustrativeDataGuard getGuard() {
        return guard;
    }

    /**
     * 已入库来源的可信度登记表，供前端把"示意数据"的引用渲染成警示样式。
     */
    public Map<String, String> credibilityBySource() {
        synchronized (credibilityBySource) {
            return new LinkedHashMap<>(credibilityBySource);
        }
    }

    /**
     * 从文档原文中解析可信度等级。
     * <p>
     * 约定写在文件头的 YAML front-matter 里：
     * <pre>
     * ---
     * credibility: illustrative
     * ---
     * </pre>
     */
    static String parseCredibility(String text) {
        if (text == null || text.isEmpty()) {
            return DEFAULT_CREDIBILITY;
        }
        Matcher matcher = FRONT_MATTER_CREDIBILITY.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return DEFAULT_CREDIBILITY;
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

            String credibility = DEFAULT_CREDIBILITY;
            if (!documents.isEmpty()) {
                credibility = parseCredibility(documents.get(0).getText());
            }
            registerCredibility(fileName, credibility);

            // 2. 分块
            List<Document> chunks = splitter.apply(documents);

            // 3. 添加来源与可信度元数据
            for (Document chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("source", fileName);
                metadata.put("credibility", credibility);
                allChunks.add(new Document(chunk.getText(), metadata));

                // illustrative 片段收集起来，供输出层硬护栏构建禁用数值集合
                if ("illustrative".equals(credibility)) {
                    illustrativeChunks.add(chunk.getText());
                }
            }

            log.info("已解析 {}（{} 块，可信度={}）", fileName, chunks.size(), credibility);
        }

        // 4. 嵌入 + 存储
        if (!allChunks.isEmpty()) {
            vectorStore.add(allChunks);
            log.info("已入库 {} 块（来源: {}）", allChunks.size(), locationPattern);
        }

        return allChunks.size();
    }

    private void registerCredibility(String fileName, String credibility) {
        if (fileName == null) {
            return;
        }
        synchronized (credibilityBySource) {
            credibilityBySource.put(fileName, credibility);
        }
    }
}
