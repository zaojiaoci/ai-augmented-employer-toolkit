package io.github.aiaugmentedemployertoolkit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评估请求体。
 *
 * @param jobDescription 岗位描述（必填）
 * @param answer         待评估的分析内容；留空则先跑一次真实分析再评估
 * @param documents      检索到的知识库上下文；留空则忠实度评分会很低（这是有意义的信号）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateRequest {

    private String jobDescription;

    private String answer;

    private List<String> documents;
}
