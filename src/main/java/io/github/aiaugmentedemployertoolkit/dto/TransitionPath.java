package io.github.aiaugmentedemployertoolkit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransitionPath {

    private List<String> transferableSkills;

    private String suggestedRole;

    private String skillGap;

    private String encouragement;

}
