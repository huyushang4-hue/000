package ykd.demo.task;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 内存中的任务会话，记录一个进行中的复杂任务的全部状态 */
@Data
public class TaskSession {

    private String taskId;
    private String userId;
    private TaskState state = TaskState.CLARIFYING;

    // ===== 澄清相关 =====
    /** 已澄清次数，上限 {@link #MAX_CLARIFY} 防止死循环 */
    private int clarifyCount = 0;
    private static final int MAX_CLARIFY = 3;
    /** 最近一次向用户提出的问题 */
    private String lastClarifyQuestion;

    // ===== 计划执行相关 =====
    /** 任务目标 */
    private String goal;
    /** AI 生成的执行计划 */
    private List<PlanStep> plan = new ArrayList<>();
    /** 当前执行到第几步 */
    private int currentStepIndex = 0;
    /** 已执行步骤的结果汇总，供 AI 在最后一步整合用 */
    private StringBuilder progressLog = new StringBuilder();

    // ===== 生命周期 =====
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastActivity = LocalDateTime.now();

    // ===== 工厂方法 =====

    public static TaskSession create(String userId) {
        TaskSession t = new TaskSession();
        t.taskId = UUID.randomUUID().toString().substring(0, 8);
        t.userId = userId;
        return t;
    }

    // ===== 状态查询 =====

    /** 10 分钟无活动自动清理 */
    public boolean isTimedOut() {
        return lastActivity.plusMinutes(10).isBefore(LocalDateTime.now());
    }

    /** 是否还能继续澄清 */
    public boolean canClarify() {
        return clarifyCount < MAX_CLARIFY;
    }

    /** 是否还有未执行的步骤 */
    public boolean hasMoreSteps() {
        return plan != null && currentStepIndex < plan.size();
    }

    /** 获取当前要执行的步骤 */
    public PlanStep currentStep() {
        if (plan == null || currentStepIndex >= plan.size()) return null;
        return plan.get(currentStepIndex);
    }

    /** 推进到下一步 */
    public void advanceStep() {
        currentStepIndex++;
        lastActivity = LocalDateTime.now();
    }

    /** 更新最后活动时间 */
    public void touch() {
        lastActivity = LocalDateTime.now();
    }

    /** 追加进度日志 */
    public void appendProgress(String text) {
        if (progressLog == null) progressLog = new StringBuilder();
        progressLog.append(text).append("\n");
    }
}
