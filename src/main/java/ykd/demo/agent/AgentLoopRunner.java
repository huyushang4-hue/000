package ykd.demo.agent;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import ykd.demo.service.AiChatService;
import ykd.demo.service.CheckpointService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent Loop 状态机：持久执行 + 崩溃恢复 + 超时 + 人工介入。
 * <p>
 * 状态表：
 * PENDING → RUNNING → COMPLETED
 *                   → PAUSED（等人确认/输入）
 *                   → FAILED（超时/不可恢复）
 *
 * 恢复：PAUSED 任务调用 resume(humanInput) 从断点继续。
 */
@Slf4j
public class AgentLoopRunner {

    private static final int MAX_RETRIES = 2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** 暂停任务注册表：sessionId → runner，供外部恢复用 */
    public static final ConcurrentHashMap<String, AgentLoopRunner> PAUSED_TASKS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final Long taskId;
    private final Long userId;
    private final AiChatService chatService;
    private final CheckpointService checkpointService;
    private final Duration timeout;

    private AgentState state = AgentState.PENDING;
    private int completedSteps = 0;
    private String lastPrompt;
    private final List<StateTransition> transitions = new ArrayList<>();

    public AgentLoopRunner(String sessionId, Long taskId, Long userId,
                           AiChatService chatService, CheckpointService checkpointService) {
        this(sessionId, taskId, userId, chatService, checkpointService, DEFAULT_TIMEOUT);
    }

    public AgentLoopRunner(String sessionId, Long taskId, Long userId,
                           AiChatService chatService, CheckpointService checkpointService, Duration timeout) {
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.userId = userId;
        this.chatService = chatService;
        this.checkpointService = checkpointService;
        this.timeout = timeout;
    }

    // ==================== 主入口 ====================

    /** 执行任务（带超时） */
    public String run(String prompt) {
        this.lastPrompt = prompt;

        // 5. 崩溃恢复：检查是否有未完成的 checkpoint
        if (completedSteps == 0 && checkpointService != null) {
            var cp = checkpointService.loadLatest(sessionId);
            if (cp != null && cp.getStatus() == ykd.demo.entity.AgentCheckpoint.CheckpointStatus.RUNNING) {
                log.info("[{}] 恢复未完成任务，已执行 {} 步", sessionId, cp.getStepCount());
                completedSteps = cp.getStepCount();
                // 从 checkpoint 恢复上次的 prompt（含任务上下文），实现"断点续跑"
                String savedPrompt = extractPrompt(cp.getStateSnapshot());
                if (savedPrompt != null && !savedPrompt.isBlank()) {
                    log.info("[{}] 已恢复上次任务上下文", sessionId);
                    lastPrompt = savedPrompt + "\n【系统】任务曾被中断，请基于以上上下文继续完成，不要从头开始。";
                }
            }
        }

        transition(AgentState.RUNNING);

        // 3. 超时：用 CompletableFuture 套超时
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            return exec.submit(() -> doRun(lastPrompt))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[{}] 任务超时({})", sessionId, timeout);
            transition(AgentState.FAILED);
            savecp("TIMEOUT after " + timeout);
            return "任务执行超时，已保存进度，稍后恢复";
        } catch (Exception e) {
            return handleError(e, lastPrompt);
        } finally {
            exec.shutdownNow();
        }
    }

    /** 从 stateSnapshot JSON 中提取上次的 prompt */
    private String extractPrompt(String snapshot) {
        if (snapshot == null || !snapshot.contains("\"prompt\"")) return null;
        try {
            return MAPPER.readTree(snapshot).path("prompt").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String doRun(String prompt) {
        try {
            String reply = chatService.chatWithTools(prompt);
            // chatWithTools 内部把 RETRYABLE 错误转成文案返回（不抛异常），这里要识别出来走重试
            if (isRetryableReply(reply)) {
                log.warn("[{}] 收到重试型回复，转任务级重试: {}", sessionId, reply);
                return retry(prompt);
            }
            transition(AgentState.COMPLETED);
            if (checkpointService != null) checkpointService.markCompleted(sessionId);
            return reply;
        } catch (Exception e) {
            return handleError(e, prompt);
        }
    }

    /** 识别 chatWithTools 内部吞掉的瞬时失败文案 */
    private boolean isRetryableReply(String reply) {
        if (reply == null || reply.isBlank()) return false;
        return reply.contains("网络不稳定") || reply.contains("正在重试中");
    }

    // ==================== 错误处理 ====================

    private String handleError(Exception e, String prompt) {
        ErrorCategory cat = ErrorCategory.classify(e);
        log.warn("[{}] 循环异常: {} → {}", sessionId, cat, e.getMessage());

        return switch (cat) {
            case RETRYABLE -> retry(prompt);
            case AUTH_REQUIRED -> {
                transition(AgentState.PAUSED);
                savecp("AUTH_REQUIRED");
                PAUSED_TASKS.put(sessionId, this);  // 4. 注册等人工介入
                yield "⚠️ 操作需要授权，请回复'确认'继续或'取消'放弃";
            }
            case DEGRADABLE -> {
                transition(AgentState.COMPLETED);
                if (checkpointService != null) checkpointService.markCompleted(sessionId);
                yield "操作部分完成，部分功能降级处理";
            }
            case FATAL -> {
                transition(AgentState.FAILED);
                savecp("FATAL: " + e.getMessage());
                yield "AI处理超时，请简化问题重新尝试";
            }
        };
    }

    private String retry(String prompt) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                Thread.sleep(2000L * (i + 1));
                String reply = chatService.chatWithTools(prompt);
                if (isRetryableReply(reply)) {
                    log.warn("[{}] 重试{}仍返回重试型回复", sessionId, i + 1);
                    continue;
                }
                transition(AgentState.COMPLETED);
                if (checkpointService != null) checkpointService.markCompleted(sessionId);
                return reply;
            } catch (Exception e) {
                log.warn("[{}] 重试{}/{}失败", sessionId, i + 1, MAX_RETRIES);
            }
        }
        transition(AgentState.FAILED);
        savecp("RETRY_EXHAUSTED");
        return "重试多次仍失败，请稍后重新尝试";
    }

    // ==================== 人工介入 ====================

    /** 4. 人工回复"确认/取消"后恢复执行 */
    public String resume(String humanInput) {
        if (state != AgentState.PAUSED) return "当前任务不在暂停状态";
        PAUSED_TASKS.remove(sessionId);

        if (humanInput.contains("取消") || humanInput.contains("不要") || humanInput.contains("放弃")) {
            transition(AgentState.FAILED);
            savecp("CANCELLED by user");
            return "任务已取消";
        }

        // 确认 → 从断点继续
        transition(AgentState.RUNNING);
        return doRun(lastPrompt + "\n【用户确认，请继续执行】");
    }

    // ==================== 持久化 ====================

    /** 1. 保存步骤级 checkpoint */
    public void stepCompleted(int totalSteps) {
        this.completedSteps++;
        savecp("step=" + completedSteps + "/" + totalSteps);
    }

    private void savecp(String snapshot) {
        if (checkpointService != null) {
            // 存 JSON：{"state": "...", "steps": N, "prompt": "..."}，prompt 用于断点恢复
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", snapshot);
            data.put("steps", completedSteps);
            data.put("prompt", lastPrompt == null ? "" : lastPrompt);
            try {
                checkpointService.save(sessionId, taskId, userId,
                        completedSteps, 10, 0, 5000, MAPPER.writeValueAsString(data));
            } catch (Exception e) {
                checkpointService.save(sessionId, taskId, userId,
                        completedSteps, 10, 0, 5000, snapshot);
            }
        }
    }

    private void transition(AgentState to) {
        transitions.add(new StateTransition(state, to, LocalDateTime.now()));
        log.info("[{}] 🔄 {} → {}", sessionId, state, to);
        state = to;
        savecp(state + " | " + completedSteps + " steps done");
    }

    // ==================== Getter ====================

    public AgentState getState() { return state; }

    public record StateTransition(AgentState from, AgentState to, LocalDateTime time) {}
}
