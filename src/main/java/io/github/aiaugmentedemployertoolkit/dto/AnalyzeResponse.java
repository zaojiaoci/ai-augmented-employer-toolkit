package io.github.aiaugmentedemployertoolkit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeResponse {

    @JsonPropertyDescription("自动化替代比率，0.0到1.0之间的数值，由任务拆解推导得出，表示「可自动化任务」占全部任务的比重；0表示几乎无任务可自动化，1表示几乎全部任务可自动化")
    private double automationRatio;

    @JsonPropertyDescription("一段简洁的分析摘要，说明该岗位的自动化潜力、哪些任务容易被替代、哪些需要人类判断")
    private String summary;

    @JsonPropertyDescription("任务级拆解：把岗位描述中的具体任务归入「可自动化 / 可增强 / 需人工判断」三类")
    private TaskBreakdown taskBreakdown;

    @JsonPropertyDescription("一句话说明 automationRatio 是如何从任务拆解中推导出来的，例如「12 项任务中 7 项可自动化」")
    private String ratioBasis;

    @JsonPropertyDescription("对该比率的防误读说明，必须明确指出这是「任务占比」而非「人员缩减比例」，且强调可增强任务才是主要机会")
    private String ratioDisclaimer;

    @JsonPropertyDescription("转岗路径建议，包含可迁移技能、目标岗位、技能差距和鼓励语")
    private TransitionPath transitionPath;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @JsonPropertyDescription("服务端对输出施加的数据可信度与立场护栏检测结果；模型不会产出此字段，由服务在解析后注入")
    private DataGuard dataGuard;

}
