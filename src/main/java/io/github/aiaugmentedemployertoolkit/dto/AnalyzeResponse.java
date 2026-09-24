package io.github.aiaugmentedemployertoolkit.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeResponse {

    @JsonPropertyDescription("自动化替代比率，0.0到1.0之间的数值，0表示完全不可替代，1表示完全可替代")
    private double automationRatio;

    @JsonPropertyDescription("一段简洁的分析摘要，说明该岗位的自动化潜力、哪些任务容易被替代、哪些需要人类判断")
    private String summary;

    @JsonPropertyDescription("转岗路径建议，包含可迁移技能、目标岗位、技能差距和鼓励语")
    private TransitionPath transitionPath;

}
