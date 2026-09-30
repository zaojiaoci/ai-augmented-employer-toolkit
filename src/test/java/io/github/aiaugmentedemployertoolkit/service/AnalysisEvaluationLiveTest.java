package io.github.aiaugmentedemployertoolkit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.aiaugmentedemployertoolkit.dto.AnalyzeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估基线测试：跑完整数据集，输出每道题的相关性 / 忠实度分数。
 * <p>
 * 默认不执行。需要同时满足两个条件才会真正运行：
 * 1. 配置了 {@code AI_DASHSCOPE_API_KEY}；
 * 2. 显式设置 {@code RUN_LIVE_EVAL=true}（避免本机有 key 时被 CI / 日常构建误触发）。
 * <p>
 * 用途：改提示词或换模型前后各跑一次，对比平均分，用来判断改动是变好还是变坏。
 * 22 条用例 × 每项 2 次 LLM 调用，实测约 9 分钟，不要放进 CI。
 */
@SpringBootTest(properties = {
        "app.evaluation.enabled=true",
        // 数据集有 22 条，需要放开自用限流
        "app.rate-limit.capacity=200",
        "app.rate-limit.refill-per-minute=200"
})
@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVAL", matches = "true")
class AnalysisEvaluationLiveTest {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEvaluationLiveTest.class);

    @Autowired
    private AnalyzeService analyzeService;

    @Autowired
    private AnalysisEvaluator analysisEvaluator;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void evaluatesFullDataset() throws Exception {
        List<EvalCase> cases = loadCases();
        assertFalse(cases.isEmpty());

        List<Row> rows = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            AnalyzeResponse response = analyzeService.analyze(evalCase.jobDescription());
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(evalCase.jobDescription()).topK(5).build());

            // 评委看到的是「完整分析结论」而不是仅摘要，否则可比对的信息太少
            String answer = renderAnswer(response);
            String transition = renderTransition(response.getTransitionPath());
            AnalysisEvaluator.Result result =
                    analysisEvaluator.evaluate(evalCase.jobDescription(), docs, answer, transition);
            rows.add(new Row(evalCase.id(), evalCase.industry(), result));
        }

        log.info("========== 评估基线 ==========");
        rows.forEach(row -> log.info("{} [{}] 相关性={} 忠实度={} 转岗质量={}",
                row.id(), row.industry(), row.result().relevancyScore(),
                row.result().faithfulnessScore(), row.result().transitionQualityScore()));

        double avgRelevancy = rows.stream().mapToDouble(r -> r.result().relevancyScore()).average().orElse(0);
        double avgFaithfulness = rows.stream().mapToDouble(r -> r.result().faithfulnessScore()).average().orElse(0);
        double avgTransition = rows.stream().mapToDouble(r -> r.result().transitionQualityScore()).average().orElse(0);
        long passed = rows.stream().filter(r -> r.result().pass()).count();

        log.info("平均相关性={} 平均忠实度={} 平均转岗质量={} 通过 {}/{}",
                avgRelevancy, avgFaithfulness, avgTransition, passed, rows.size());
        log.info("==============================");

        assertTrue(rows.stream().allMatch(r -> r.result().relevancyScore() >= 0),
                "每条用例都应产出评分");
        // 宽松的回归门槛：这套分数是「相对基线」而非绝对质量分（我们没有标注过的标准答案），
        // 门槛只用来发现明显劣化，不要调高到接近当前均值。
        assertTrue(avgRelevancy >= 0.3, "平均相关性过低，提示词或模型可能出问题：" + avgRelevancy);
        assertTrue(avgTransition >= 0.2, "平均转岗建议质量过低，转岗路径可能退化：" + avgTransition);
    }

    private static String renderAnswer(AnalyzeResponse response) {
        StringBuilder sb = new StringBuilder(response.getSummary() == null ? "" : response.getSummary());
        if (response.getTaskBreakdown() != null) {
            io.github.aiaugmentedemployertoolkit.dto.TaskBreakdown tb = response.getTaskBreakdown();
            sb.append("\n可自动化任务：").append(String.join("、", nullSafe(tb.getAutomatable())));
            sb.append("\n可增强任务：").append(String.join("、", nullSafe(tb.getAugmentable())));
            sb.append("\n需人工判断任务：").append(String.join("、", nullSafe(tb.getHumanOnly())));
        }
        if (response.getTransitionPath() != null) {
            sb.append("\n建议转岗方向：").append(response.getTransitionPath().getSuggestedRole());
        }
        return sb.toString();
    }

    private static String renderTransition(io.github.aiaugmentedemployertoolkit.dto.TransitionPath path) {
        if (path == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (path.getSuggestedRole() != null) {
            sb.append("建议转岗方向：").append(path.getSuggestedRole()).append("\n");
        }
        if (path.getSkillGap() != null) {
            sb.append("能力缺口：").append(path.getSkillGap()).append("\n");
        }
        if (path.getEncouragement() != null) {
            sb.append("鼓励语：").append(path.getEncouragement());
        }
        return sb.toString();
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<EvalCase> loadCases() throws Exception {
        try (InputStream in = new ClassPathResource("eval-cases.json").getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ObjectMapper().readValue(json, new TypeReference<List<EvalCase>>() {
            });
        }
    }

    record EvalCase(String id, String industry, String jobDescription) {
    }

    record Row(String id, String industry, AnalysisEvaluator.Result result) {
    }
}
