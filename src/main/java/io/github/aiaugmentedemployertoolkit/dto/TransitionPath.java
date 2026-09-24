package io.github.aiaugmentedemployertoolkit.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransitionPath {

    @JsonPropertyDescription("可迁移的核心技能列表，现有岗位中可以直接迁移到新岗位的技能")
    private List<String> transferableSkills;

    @JsonPropertyDescription("建议转向的目标岗位名称")
    private String suggestedRole;

    @JsonPropertyDescription("需要补充学习的关键技能，1-2句话描述")
    private String skillGap;

    @JsonPropertyDescription("一句鼓励性的话，强调该员工不是从零开始，而是在已有经验上叠加新能力")
    private String encouragement;

}
