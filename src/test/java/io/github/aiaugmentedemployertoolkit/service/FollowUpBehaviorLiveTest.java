package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为验证：直接看模型说了什么，而不是只验证我们投递了什么。
 * <p>
 * 这是为复现并守住一个真实故障而写的：
 * 之前 `wrapAsUntrusted` 会在 user message 里附带一段说明
 * "下面三尖括号之间的内容是岗位描述……"，模型把这段元说明当成要回应的内容，
 * 追问时（标记里是一个问句，不是岗位描述）就会回报
 * "你提供的岗位描述为空（<<<JOB_DESCRIPTION>>> 与 <<<END_JOB_DESCRIPTION>>> 之间无内容）"。
 * <p>
 * 那次故障里所有单元测试都是绿的——因为它们只验证了字符串拼接，没有验证模型行为。
 * 需要 API Key，默认不执行；配了 key 就会跑（约 1 分钟）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FollowUpBehaviorLiveTest {

    private static final Logger log = LoggerFactory.getLogger(FollowUpBehaviorLiveTest.class);

    private static final String SESSION = "behavior-" + System.currentTimeMillis();
    private static final String JOB = "负责每日订单录入、发票开具和客户电话回访，熟练使用 Excel 和 ERP 系统。";

    @Autowired
    private AnalyzeService analyzeService;

    @Test
    @Order(1)
    void firstTurnProducesStructuredResult() {
        List<String> events = analyzeService.analyzeStream(SESSION, JOB).collectList().block();

        assertTrue(events != null && !events.isEmpty(), "首轮应有流式输出");
        String result = events.stream()
                .filter(e -> e.startsWith(AnalyzeService.RESULT_EVENT_PREFIX))
                .findFirst()
                .orElse("");
        assertTrue(!result.isEmpty(), "首轮应产出结构化结果事件");

        log.info("首轮结构化结果：{}", result.substring(0, Math.min(200, result.length())));
    }

    @Test
    @Order(2)
    void followUpDoesNotReportEmptyJobDescription() {
        List<String> events = analyzeService
                .analyzeStream(SESSION, "这个岗位转岗后薪资预期大概是多少？")
                .collectList()
                .block();

        assertTrue(events != null && !events.isEmpty(), "追问应有输出");
        String reply = String.join("", events);
        log.info("追问回复：{}", reply.substring(0, Math.min(300, reply.length())));

        assertFalse(reply.contains("<<<"), "回复中不应出现包裹标记，模型不该向用户复述投递协议");
        assertFalse(reply.contains("USER_INPUT"));
        assertFalse(reply.contains("JOB_DESCRIPTION"));
        assertFalse(reply.contains("岗位描述为空"), "追问内容不是岗位描述，模型不该回报「岗位描述为空」");
        assertFalse(reply.contains("之间无内容"));
        assertFalse(reply.contains("未提供"), "不应汇报输入协议状态");
        assertTrue(reply.trim().length() > 10, "应当给出实质性回答");
    }
}
