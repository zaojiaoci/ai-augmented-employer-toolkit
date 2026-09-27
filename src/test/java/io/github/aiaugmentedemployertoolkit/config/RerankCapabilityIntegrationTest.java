package io.github.aiaugmentedemployertoolkit.config;

import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 真实 Spring 上下文下的能力分级验证：
 * 开启 {@code app.rag.rerank-enabled} 后，spring-ai-alibaba 的 DashScope rerank 自动配置
 * 应当已经提供了 RerankModel，工厂因此可以升级为重排 Advisor。
 * <p>
 * 这里只验证 Bean 装配，不发起任何网络请求。
 */
@SpringBootTest(properties = "app.rag.rerank-enabled=true")
class RerankCapabilityIntegrationTest {

    @Autowired
    private RagAdvisorFactory ragAdvisorFactory;

    @Test
    void upgradesToRerankAdvisorInRealContext() {
        assertInstanceOf(RetrievalRerankAdvisor.class, ragAdvisorFactory.create(),
                "未找到 RerankModel，spring-ai-alibaba 的 rerank 自动配置可能未生效");
    }
}
