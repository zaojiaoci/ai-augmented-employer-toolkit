package io.github.aiaugmentedemployertoolkit.config;

import io.github.aiaugmentedemployertoolkit.tool.SalaryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ChatClient 装配。
 * <p>
 * 全应用只有一个 ChatClient：Advisor、Tool、系统提示词都在这里装配一次。
 * 检索策略由 {@link RagAdvisorFactory} 决定，便于按配置在「原生检索」和「rerank 重排」之间切换。
 */
@Configuration
public class ChatClientConfig {

    @Value("classpath:prompts/analyze-prompt.txt")
    private Resource promptResource;

    @Bean
    public SystemPrompt analyzeSystemPrompt() throws IOException {
        return new SystemPrompt(promptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 SalaryTool salaryTool,
                                 SystemPrompt systemPrompt,
                                 RagAdvisorFactory ragAdvisorFactory) {

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt.text())
                .defaultAdvisors(ragAdvisorFactory.create())
                .defaultTools(salaryTool)
                .build();
    }
}
