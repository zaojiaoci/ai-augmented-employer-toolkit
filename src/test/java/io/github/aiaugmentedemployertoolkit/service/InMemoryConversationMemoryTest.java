package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationMemoryTest {

    @Test
    void storesHistoryAsUserAssistantPairs() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100);
        memory.append("s1", "岗位描述", "分析结论");

        List<Message> history = memory.history("s1");

        assertEquals(2, history.size());
        assertEquals(MessageType.USER, history.get(0).getMessageType());
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());
        assertEquals("分析结论", history.get(1).getText());
    }

    @Test
    void historyOfUnknownSessionIsEmpty() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100);
        assertTrue(memory.history("unknown").isEmpty());
    }

    @Test
    void trimsByWholeTurnSoHistoryStartsWithUserMessage() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(4, Duration.ofMinutes(60), 100);

        memory.append("s1", "问1", "答1");
        memory.append("s1", "问2", "答2");
        memory.append("s1", "问3", "答3");

        List<Message> history = memory.history("s1");

        assertEquals(4, history.size());
        assertEquals(MessageType.USER, history.get(0).getMessageType());
        assertEquals("问2", history.get(0).getText());
        assertEquals("答3", history.get(3).getText());
    }

    @Test
    void evictsSessionsIdleLongerThanTtl() throws InterruptedException {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMillis(60), 100);

        memory.append("s1", "问", "答");
        assertEquals(1, memory.sessionCount());

        Thread.sleep(120);

        assertEquals(1, memory.evictExpired());
        assertEquals(0, memory.sessionCount());
        assertTrue(memory.history("s1").isEmpty());
    }

    @Test
    void keepsRecentSessionsDuringEviction() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100);
        memory.append("s1", "问", "答");

        assertEquals(0, memory.evictExpired());
        assertEquals(1, memory.sessionCount());
    }

    @Test
    void enforcesMaxSessions() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 2);

        memory.append("s1", "问", "答");
        memory.append("s2", "问", "答");
        memory.append("s3", "问", "答");

        assertTrue(memory.sessionCount() <= 2, "会话数不应超过上限，实际 " + memory.sessionCount());
    }

    @Test
    void ignoresBlankSessionId() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(16, Duration.ofMinutes(60), 100);

        memory.append(null, "问", "答");
        memory.append("  ", "问", "答");

        assertEquals(0, memory.sessionCount());
    }

    @Test
    void toleratesDegenerateConfiguration() {
        // 构造函数对非法值做下限保护，不应抛出
        InMemoryConversationMemory memory = new InMemoryConversationMemory(0, Duration.ZERO, 0);
        memory.append("s1", "问", "答");

        assertEquals(1, memory.sessionCount());
        assertTrue(memory.history(null).isEmpty());
    }
}
