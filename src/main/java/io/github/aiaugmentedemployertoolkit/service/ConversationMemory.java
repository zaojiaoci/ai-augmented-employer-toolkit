package io.github.aiaugmentedemployertoolkit.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话记忆存储抽象。
 * <p>
 * 之前业务代码直接依赖内存实现的具体类，替换存储需要改业务代码。
 * 这里抽出接口，内存实现只是默认选项，生产环境可替换为 Redis / 数据库实现。
 */
public interface ConversationMemory {

    /**
     * 获取指定会话的历史消息快照，并刷新该会话的活跃时间。
     */
    List<Message> history(String sessionId);

    /**
     * 追加一轮对话（user + assistant），并裁剪超出窗口的旧消息。
     * 裁剪以「一轮」为单位，保证历史始终以 user 消息开头且 user/assistant 成对。
     */
    void append(String sessionId, String userText, String assistantText);

    /**
     * 清理超过 TTL 未访问的会话，返回被清理的数量。
     */
    int evictExpired();

    /**
     * 当前存活的会话数量。
     */
    int sessionCount();
}
