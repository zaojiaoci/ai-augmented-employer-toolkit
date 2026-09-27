package io.github.aiaugmentedemployertoolkit.config;

/**
 * 系统提示词载体。
 * <p>
 * 用类型包装而不是直接注册一个 {@code String} Bean，避免与容器中其他 String 依赖产生歧义。
 */
public record SystemPrompt(String text) {

    public SystemPrompt {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("系统提示词为空，请检查 prompts/analyze-prompt.txt");
        }
    }
}
