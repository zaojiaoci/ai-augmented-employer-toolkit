package io.github.aiaugmentedemployertoolkit.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话记忆存储，支持多轮对话。
 * <p>
 * 使用 ConcurrentHashMap 按 sessionId 存储对话历史，
 * 滑动窗口裁剪保留最近 N 轮对话。
 * 生产环境可替换为 Redis / 数据库持久化实现。
 */
@Component
public class ConversationMemoryStore {

    private final Map<String, Deque<Message>> store = new ConcurrentHashMap<>();

    /** 最大保留轮数（每轮 = 1条 user + 1条 assistant），默认 8 轮 = 16 条消息 */
    private final int maxMessages = 16;

    /**
     * 获取指定会话的历史消息快照
     */
    public List<Message> history(String sessionId) {
        Deque<Message> deque = store.get(sessionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /**
     * 追加一轮对话（user + assistant）到历史，并裁剪超出窗口的旧消息
     */
    public void append(String sessionId, String userText, String assistantText) {
        Deque<Message> deque = store.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new UserMessage(userText));
            deque.addLast(new AssistantMessage(assistantText));
            while (deque.size() > maxMessages) {
                deque.pollFirst();
            }
        }
    }
}
