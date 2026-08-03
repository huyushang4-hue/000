package ykd.demo.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.service.AiChatService;
import ykd.demo.tool.Tool;

import java.util.*;
import java.util.concurrent.*;

/**
 * 任务编排服务 —— doToolLoop 外面薄薄一层状态机。
 *
 * 职责：
 * 1. AI 调用后检查 InterruptSignal（ask_user / create_plan），创建对应 TaskSession
 * 2. 管理多轮澄清：接收用户回复 → 喂回 AI → 判断信息是否齐全
 * 3. 管理多步骤执行：逐步推进 plan → 汇总结果
 * 4. 定时清理超时任务
 */
@Slf4j
@Service
public class TaskOrchestratorService {

    private final ConcurrentHashMap<String, TaskSession> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> userTaskIndex = new ConcurrentHashMap<>();

    private final AiChatService aiChatService;
    private final List<Tool> tools;
    private final ScheduledExecutorService cleanupExecutor;

    public TaskOrchestratorService(AiChatService aiChatService, List<Tool> tools) {
        this.aiChatService = aiChatService;
        this.tools = tools;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanup, 30, 30, TimeUnit.SECONDS);
    }

    // ==================== 对外 API ====================

    /** 用户是否处于等待澄清状态（供 ILinkBotService 判断是否跳过防抖） */
    public boolean isAwaitingClarification(String userId) {
        TaskSession task = getActiveTask(userId);
        return task != null && task.getState() == TaskState.CLARIFYING;
    }

    /** 是否有正在执行中的任务 */
    public boolean hasActiveTask(String userId) {
        return getActiveTask(userId) != null;
    }

    /** 获取用户当前活跃任务 */
    public TaskSession getActiveTask(String userId) {
        String taskId = userTaskIndex.get(userId);
        if (taskId != null) {
            TaskSession task = activeTasks.get(taskId);
            if (task != null && !task.isTimedOut()) return task;
            // 超时清理
            if (task != null) removeTask(task);
        }
        return null;
    }

    // ==================== 中断信号处理 ====================

    /**
     * AI 调用完成后的信号处理（在 flushBatch 线程调用）。
     * @return 处理后的回复文本，如果无需特殊处理则返回原始 reply
     */
    public String handleSignal(String userId, InterruptSignal signal, String reply) {
        if (signal == null) return reply;

        return switch (signal.getType()) {
            case CLARIFY -> handleClarifySignal(userId, reply);
            case PLAN    -> handlePlanSignal(userId, signal.getData());
        };
    }

    /** AI 调了 ask_user：创建 CLARIFYING 任务，返回问题文本给用户 */
    private String handleClarifySignal(String userId, String question) {
        TaskSession task = TaskSession.create(userId);
        task.setState(TaskState.CLARIFYING);
        task.setClarifyCount(1);
        task.setLastClarifyQuestion(question);
        registerTask(task);
        log.info("[{}] 创建澄清任务 {}: {}", userId, task.getTaskId(), question);
        return question;
    }

    /** AI 调了 create_plan：解析计划，执行第一步 */
    private String handlePlanSignal(String userId, String planJson) {
        TaskSession task = TaskSession.create(userId);
        task.setState(TaskState.EXECUTING);
        parsePlan(task, planJson);

        if (task.getPlan().isEmpty()) {
            // AI 没生成有效计划，降级为简单回答
            log.warn("[{}] create_plan 未生成有效步骤", userId);
            return "抱歉，我无法拆解这个任务，请尝试更具体的描述。";
        }

        registerTask(task);
        log.info("[{}] 创建执行任务 {}: {} ({} 步)", userId, task.getTaskId(), task.getGoal(), task.getPlan().size());

        // 立即执行第一步
        return executeNextStepInternal(task);
    }

    // ==================== 澄清流程 ====================

    /**
     * 用户回复了澄清问题（在 iLink 消息回调线程调用，绕过防抖）。
     * 把回复喂给 AI，判断信息是否齐全。
     */
    public String continueClarification(String userId, String userReply) {
        TaskSession task = getActiveTask(userId);
        if (task == null || task.getState() != TaskState.CLARIFYING) {
            // 状态异常，降级为普通处理
            return null; // 返回 null 让调用方走正常流程
        }

        task.setClarifyCount(task.getClarifyCount() + 1);
        task.touch();
        log.info("[{}] 澄清第 {} 轮回复: {}", userId, task.getClarifyCount(), userReply);

        // 如果达到澄清上限，追加提示让 AI 强制执行
        String prompt = userReply;
        if (!task.canClarify()) {
            prompt = userReply + "\n[系统提示：已澄清" + task.getClarifyCount() + "次达到上限，"
                    + "请根据已有信息继续执行。若实在无法确定，给出最佳推测。]";
        }

        // 调用 AI（同一线程：ThreadLocal 可用）
        List<Map<String, Object>> entries = List.of(Map.of("type", "TEXT", "text", prompt));
        String reply = aiChatService.processDebounced(userId, entries);

        // 检查是否再次中断
        InterruptSignal signal = InterruptSignal.get();
        if (signal != null && signal.getType() == InterruptSignal.Type.CLARIFY) {
            InterruptSignal.clear();
            task.setLastClarifyQuestion(reply);
            return reply; // 继续澄清
        }

        // 检查是否生成了计划
        if (signal != null && signal.getType() == InterruptSignal.Type.PLAN) {
            String planResult = handlePlanSignal(userId, signal.getData());
            InterruptSignal.clear();
            // 清除旧的澄清状态（handlePlanSignal 创建了新 task）
            removeTask(task);
            return planResult;
        }

        // 没有中断 → 任务完成
        InterruptSignal.clear();
        task.setState(TaskState.COMPLETED);
        removeTask(task);
        log.info("[{}] 澄清完成，任务结束", userId);
        return reply;
    }

    // ==================== 多步骤执行 ====================

    /** 是否还有未执行的步骤（供 ILinkBotService 判断是否要继续调度） */
    public boolean hasMoreSteps(String userId) {
        TaskSession task = getActiveTask(userId);
        return task != null && task.getState() == TaskState.EXECUTING && task.hasMoreSteps();
    }

    /** 执行下一步，返回给用户看的进度文本。无更多步骤返回 null。 */
    public String executeNextStep(String userId) {
        TaskSession task = getActiveTask(userId);
        if (task == null || task.getState() != TaskState.EXECUTING) return null;
        if (!task.hasMoreSteps()) {
            completeTask(task);
            return null;
        }
        return executeNextStepInternal(task);
    }

    /** 内部：执行当前步骤 + 推进 */
    private String executeNextStepInternal(TaskSession task) {
        PlanStep step = task.currentStep();
        if (step == null) return null;

        task.touch();
        int stepNum = step.getIndex();
        int total = task.getPlan().size();
        String desc = step.getDescription();

        String result;
        if (step.getTool() != null && !step.getTool().isBlank() && !"null".equals(step.getTool())) {
            // 有工具 → 直接调用
            result = executeToolDirectly(step.getTool(), step.getToolArgs());
        } else {
            // 无工具 → 调 AI 完成此步骤
            result = callAiForStep(task, step);
        }

        step.setResult(result);
        step.setCompleted(true);
        task.appendProgress("Step " + stepNum + " (" + desc + "): " + result);
        task.advanceStep();

        if (!task.hasMoreSteps()) {
            // 最后一步 → 生成最终汇总
            return generateFinalSummary(task);
        }

        // 中间步骤 → 返回进度 + 结果
        return "✅ [" + stepNum + "/" + total + "] " + desc + "\n" + result
                + "\n\n⏳ 正在执行第 " + (stepNum + 1) + " 步...";
    }

    /** 调 AI 完成一个不需要工具调用的步骤 */
    private String callAiForStep(TaskSession task, PlanStep step) {
        String prompt = "你正在执行一个多步骤任务。\n"
                + "任务目标：" + task.getGoal() + "\n"
                + "已完成步骤及结果：\n" + task.getProgressLog() + "\n"
                + "当前步骤（第 " + step.getIndex() + " 步）：" + step.getDescription() + "\n"
                + "请完成此步骤并直接给出结果。不要调用任何工具。";

        List<Map<String, Object>> entries = List.of(Map.of("type", "TEXT", "text", prompt));
        String reply = aiChatService.processDebounced(task.getUserId(), entries);

        InterruptSignal signal = InterruptSignal.get();
        if (signal != null && signal.getType() == InterruptSignal.Type.CLARIFY) {
            // 执行中遇到需要澄清的情况，切回 CLARIFYING
            InterruptSignal.clear();
            task.setState(TaskState.CLARIFYING);
            task.setLastClarifyQuestion(reply);
            task.setClarifyCount(task.getClarifyCount() + 1);
            return "[暂停：需要用户补充信息]";
        }
        InterruptSignal.clear();
        return reply;
    }

    /** 所有步骤完成 → 调用 AI 生成最终汇总 */
    private String generateFinalSummary(TaskSession task) {
        String prompt = "你正在完成一个多步骤任务的最终汇总。\n"
                + "任务目标：" + task.getGoal() + "\n"
                + "各步骤执行结果：\n" + task.getProgressLog() + "\n"
                + "请整合以上信息，生成完整、有条理的最终回复给用户。";

        List<Map<String, Object>> entries = List.of(Map.of("type", "TEXT", "text", prompt));
        String reply = aiChatService.processDebounced(task.getUserId(), entries);
        InterruptSignal.clear(); // 最终汇总忽略中断

        completeTask(task);
        return reply;
    }

    /** 标记任务完成并清理 */
    private void completeTask(TaskSession task) {
        task.setState(TaskState.COMPLETED);
        removeTask(task);
        log.info("[{}] 任务 {} 完成", task.getUserId(), task.getTaskId());
    }

    // ==================== 任务生命周期 ====================

    private void registerTask(TaskSession task) {
        // 先清理用户旧任务
        String oldTaskId = userTaskIndex.get(task.getUserId());
        if (oldTaskId != null) {
            TaskSession old = activeTasks.remove(oldTaskId);
            if (old != null) old.setState(TaskState.CANCELLED);
        }
        activeTasks.put(task.getTaskId(), task);
        userTaskIndex.put(task.getUserId(), task.getTaskId());
    }

    private void removeTask(TaskSession task) {
        activeTasks.remove(task.getTaskId());
        userTaskIndex.remove(task.getUserId(), task.getTaskId());
    }

    /** 定时清理超时任务 */
    private void cleanup() {
        List<TaskSession> timedOut = new ArrayList<>();
        for (TaskSession task : activeTasks.values()) {
            if (task.isTimedOut()) timedOut.add(task);
        }
        for (TaskSession task : timedOut) {
            task.setState(TaskState.CANCELLED);
            removeTask(task);
            log.info("清理超时任务 [{}] {}", task.getUserId(), task.getTaskId());
        }
    }

    // ==================== 工具方法 ====================

    /** 解析 create_plan 返回的 JSON，填充 TaskSession.plan */
    private void parsePlan(TaskSession task, String planJson) {
        try {
            JsonObject root = JsonParser.parseString(planJson).getAsJsonObject();
            task.setGoal(root.has("goal") ? root.get("goal").getAsString() : "未命名任务");

            if (!root.has("steps")) return;
            JsonArray stepsArr = root.getAsJsonArray("steps");
            for (int i = 0; i < stepsArr.size(); i++) {
                JsonObject s = stepsArr.get(i).getAsJsonObject();
                PlanStep step = new PlanStep();
                step.setIndex(s.has("index") ? s.get("index").getAsInt() : i + 1);
                step.setDescription(s.has("description") ? s.get("description").getAsString() : "步骤" + (i + 1));
                step.setTool(s.has("tool") && !s.get("tool").isJsonNull() ? s.get("tool").getAsString() : null);
                step.setToolArgs(s.has("tool_args") && !s.get("tool_args").isJsonNull()
                        ? s.get("tool_args").toString() : null);
                step.setCompleted(false);
                task.getPlan().add(step);
            }
        } catch (Exception e) {
            log.error("解析 create_plan JSON 失败", e);
        }
    }

    /** 根据工具名 + 参数直接调用工具（不经过 AI） */
    private String executeToolDirectly(String toolName, String toolArgsJson) {
        JsonObject args;
        try {
            args = (toolArgsJson != null && !toolArgsJson.isBlank())
                    ? JsonParser.parseString(toolArgsJson).getAsJsonObject()
                    : new JsonObject();
        } catch (Exception e) {
            log.warn("工具参数解析失败: {}", toolArgsJson, e);
            args = new JsonObject();
        }

        for (Tool tool : tools) {
            if (tool.getDefinition().getName().equals(toolName)) {
                log.info("编排器直接执行工具: {} -> {}", toolName, toolArgsJson);
                try {
                    return tool.execute(args);
                } catch (Exception e) {
                    log.error("工具 {} 执行失败", toolName, e);
                    return "工具执行失败：" + e.getMessage();
                }
            }
        }
        log.warn("未知工具: {}", toolName);
        return "未知工具：" + toolName;
    }
}
