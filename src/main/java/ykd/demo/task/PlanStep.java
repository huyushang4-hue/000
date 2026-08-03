package ykd.demo.task;

import lombok.Data;

/** AI 生成的单个执行步骤 */
@Data
public class PlanStep {
    /** 步骤序号 */
    private int index;
    /** 给人看的步骤描述 */
    private String description;
    /** 需要调用的工具名（不需要工具则为 null） */
    private String tool;
    /** 工具参数 JSON 字符串（不需要工具则为 null） */
    private String toolArgs;
    /** 执行结果（执行后填充） */
    private String result;
    /** 是否已完成 */
    private boolean completed;
}
