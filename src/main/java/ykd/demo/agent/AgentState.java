package ykd.demo.agent;

/**
 * Agent Loop 状态枚举：每个任务在这些状态间按规则跃迁。
 */
public enum AgentState {
    /** 任务刚创建，等待开始 */
    PENDING,
    /** 任务执行中（包含 AI 思考+工具调用+观察结果） */
    RUNNING,
    /** 需要人工介入，暂停等待 */
    PAUSED,
    /** 目标达成，任务完成 */
    COMPLETED,
    /** 无法恢复，任务失败 */
    FAILED
}
