package io.github.aiaugmentedemployertoolkit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话记忆，带滑动窗口裁剪与 TTL 过期清理。
 * <p>
 * 相比原实现补了三件事：
 * 1. 裁剪以「一轮」为单位，避免历史以 assistant 消息开头（部分模型 API 会拒绝）；
 * 2. 有 TTL 与定期清理，避免 session 无限堆积导致内存泄漏；
 * 3. 限制最大会话数，防止被大量随机 sessionId 打爆。
 */
@Component
public class InMemoryConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationMemory.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 最大保留消息条数（每轮 = 1 条 user + 1 条 assistant），应为偶数 */
    private final int maxMessages;

    private final long ttlMillis;

    /** 最大会话数，超出后优先清理过期会话，其次淘汰最久未访问的 */
    private final int maxSessions;

    @Autowired
    public InMemoryConversationMemory(
            @Value("${app.memory.max-messages:16}") int maxMessages,
            @Value("${app.memory.ttl-minutes:60}") long ttlMinutes,
            @Value("${app.memory.max-sessions:5000}") int maxSessions) {
        this(maxMessages, Duration.ofMinutes(Math.max(1, ttlMinutes)), maxSessions);
    }

    /**
     * 直接指定 TTL，便于测试使用毫秒级过期时间。
     */
    public InMemoryConversationMemory(int maxMessages, Duration ttl, int maxSessions) {
        this.maxMessages = Math.max(2, maxMessages);
        this.ttlMillis = Math.max(1, ttl == null ? Duration.ofMinutes(60).toMillis() : ttl.toMillis());
        this.maxSessions = Math.max(1, maxSessions);
    }

    @Override
    public List<Message> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return List.of();
        }
        session.touch();
        synchronized (session.messages) {
            return new ArrayList<>(session.messages);
        }
    }

    @Override
    public void append(String sessionId, String userText, String assistantText) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        session.touch();
        synchronized (session.messages) {
            session.messages.addLast(new UserMessage(userText));
            session.messages.addLast(new AssistantMessage(assistantText));
            trimByTurn(session.messages);
        }
        if (sessions.size() > maxSessions) {
            enforceMaxSessions();
        }
    }

    @Override
    public int evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.isExpired(now, ttlMillis)) {
                if (sessions.remove(entry.getKey(), session)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("会话记忆清理：移除 {} 个过期会话，剩余 {}", removed, sessions.size());
        }
        return removed;
    }

    @Scheduled(fixedDelayString = "${app.memory.cleanup-interval-ms:600000}")
    public void scheduledEvict() {
        evictExpired();
    }

    @Override
    public int sessionCount() {
        return sessions.size();
    }

    long ttlMillis() {
        return ttlMillis;
    }

    private void enforceMaxSessions() {
        evictExpired();
        while (sessions.size() > maxSessions) {
            String lru = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                long access = entry.getValue().lastAccessMillis();
                if (access < oldest) {
                    oldest = access;
                    lru = entry.getKey();
                }
            }
            if (lru == null || sessions.remove(lru) == null) {
                break;
            }
            log.warn("会话数超过上限 {}，淘汰最久未访问会话", maxSessions);
        }
    }

    /**
     * 以「一轮」为单位裁剪，保证剩余历史成对且以 user 开头。
     */
    private void trimByTurn(Deque<Message> messages) {
        while (messages.size() > maxMessages) {
            messages.pollFirst();
            if (!messages.isEmpty()) {
                messages.pollFirst();
            }
        }
    }

    private static final class Session {
        private final Deque<Message> messages = new ArrayDeque<>();
        private volatile long lastAccess = System.currentTimeMillis();

        void touch() {
            lastAccess = System.currentTimeMillis();
        }

        long lastAccessMillis() {
            return lastAccess;
        }

        boolean isExpired(long now, long ttl) {
            return now - lastAccess > ttl;
        }
    }
}
