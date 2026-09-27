package io.github.aiaugmentedemployertoolkit.config;

import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import com.alibaba.cloud.ai.model.RerankModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证「能力分级」：默认走 Spring AI 原生检索，
 * 只有开启开关且容器中存在 RerankModel 时才升级为 rerank 重排。
 */
@SuppressWarnings("unchecked")
class RagAdvisorFactoryTest {

    private final VectorStore vectorStore = mock(VectorStore.class);

    @Test
    void usesPlainRetrievalByDefault() {
        RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, providerOf(null),
                false, 5, 0.6, 20, 0.3);

        assertInstanceOf(QuestionAnswerAdvisor.class, factory.create());
    }

    @Test
    void upgradesToRerankWhenEnabledAndModelPresent() {
        RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, providerOf(mock(RerankModel.class)),
                true, 5, 0.6, 20, 0.3);

        assertInstanceOf(RetrievalRerankAdvisor.class, factory.create());
    }

    @Test
    void fallsBackWhenRerankEnabledButModelMissing() {
        RagAdvisorFactory factory = new RagAdvisorFactory(vectorStore, providerOf(null),
                true, 5, 0.6, 20, 0.3);

        assertInstanceOf(QuestionAnswerAdvisor.class, factory.create(),
                "开启开关但容器里没有 RerankModel 时不应启动失败");
    }

    private static ObjectProvider<RerankModel> providerOf(RerankModel instance) {
        ObjectProvider<RerankModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
