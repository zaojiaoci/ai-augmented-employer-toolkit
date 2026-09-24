package io.github.aiaugmentedemployertoolkit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeResponse {

    private double automationRatio;

    private String summary;

    private TransitionPath transitionPath;

}
