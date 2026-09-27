package io.github.aiaugmentedemployertoolkit.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务级拆解结果。
 * <p>
 * 岗位不是原子单位，任务才是。相比直接给一个"替代率"分数，
 * 先把岗位拆成三类任务，替代率再由这三类的占比推导出来，
 * 这样结论可解释、可操作，也避免被直接读成"裁员比例"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskBreakdown {

    @JsonPropertyDescription("可由 AI 或自动化直接完成的任务清单，每条一句话，必须来自岗位描述中的具体任务，不要泛泛而谈")
    private List<String> automatable;

    @JsonPropertyDescription("AI 可显著提升效率、但仍需人类判断或把关的任务清单，每条一句话")
    private List<String> augmentable;

    @JsonPropertyDescription("必须由人类完成、AI 难以替代的任务清单（如情感沟通、异常处理、现场判断），每条一句话")
    private List<String> humanOnly;

}
