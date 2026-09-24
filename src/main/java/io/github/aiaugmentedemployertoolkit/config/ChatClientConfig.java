package io.github.aiaugmentedemployertoolkit.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class ChatClientConfig {

    @Value("classpath:prompts/analyze-prompt.txt")
    private Resource promptResource;

    @Bean
    public ChatClient analyzeChatClient(ChatModel chatModel) throws IOException {
        String systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
    }

    /**
     * 流式对话专用 ChatClient，与 analyzeChatClient 分开，
     * 避免 BeanOutputConverter 的格式指令污染流式输出。
     */
    @Bean
    public ChatClient streamingChatClient(ChatModel chatModel) throws IOException {
        String systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
    }

}
