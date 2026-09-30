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
 * @param transitionContext 转岗建议文本（建议岗位+能力缺口+鼓励语拼接）；留空则转岗质量维度被跳过。
 *                          若 answer 由本端点自动分析得到，则会从分析结果里提取转岗文本
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateRequest {

    private String jobDescription;

    private String answer;

    private List<String> documents;

    private String transitionContext;
}
