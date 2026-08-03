package ykd.demo.task;

/** 任务会话状态 */
public enum TaskState {
    /** 等待用户澄清/补全信息 */
    CLARIFYING,
    /** 正在执行计划步骤 */
    EXECUTING,
    /** 任务完成 */
    COMPLETED,
    /** 超时或用户取消 */
    CANCELLED
}
