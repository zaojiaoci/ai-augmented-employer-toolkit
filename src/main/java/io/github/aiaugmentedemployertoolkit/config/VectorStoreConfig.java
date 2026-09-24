package io.github.aiaugmentedemployertoolkit.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自适应向量存储配置。
 * <p>
 * 默认使用 SimpleVectorStore（内存），适合开发和 demo 场景。
 * 当配置了 spring.ai.vectorstore.pgvector.url 后，
 * spring-ai-starter-vector-store-pgvector 的自动配置会接管，
 * 提供 PgVectorStore Bean（持久化到 PostgreSQL）。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 内存向量存储（默认），仅在未配置 pgvector 时生效。
     */
    @Bean
    @ConditionalOnProperty(
            name = "spring.ai.vectorstore.pgvector.url",
            matchIfMissing = true,
            havingValue = "__NEVER_MATCH__"
    )
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

}
