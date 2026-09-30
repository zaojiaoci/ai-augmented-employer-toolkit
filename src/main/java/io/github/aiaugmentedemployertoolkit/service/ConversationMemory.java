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

    /**
     * 清空指定会话的对话上下文。
     * <p>
     * 用于「跨会话评测」：注入事实后清空当前对话，只保留长期记忆（若有），
     * 再提问——以此区分「上下文窗口还记得」与「长期记忆真的记住了」。
     * 对纯内存实现而言，清空即等同于完全遗忘。
     */
    void clear(String sessionId);
}
