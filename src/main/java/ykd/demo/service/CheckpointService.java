package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ykd.demo.entity.AgentCheckpoint;
import ykd.demo.entity.ScheduledTask;
import ykd.demo.repository.AgentCheckpointRepository;
import ykd.demo.repository.ScheduledTaskRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointService {

    private final AgentCheckpointRepository checkpointRepository;

    /** 保存检查点 */
    public AgentCheckpoint save(String sessionId, Long taskId, Long userId,
                                 int stepCount, int maxSteps, int tokensUsed,
                                 int tokenBudget, String stateSnapshot) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(sessionId);
        cp.setTaskId(taskId);
        cp.setUserId(userId);
        cp.setStepCount(stepCount);
        cp.setMaxSteps(maxSteps);
        cp.setTokensUsed(tokensUsed);
        cp.setTokenBudget(tokenBudget);
        cp.setStateSnapshot(stateSnapshot);
        cp.setStatus(AgentCheckpoint.CheckpointStatus.RUNNING);
        return checkpointRepository.save(cp);
    }

    /** 加载最近的检查点 */
    public AgentCheckpoint loadLatest(String sessionId) {
        return checkpointRepository.findTopBySessionIdOrderByCreatedAtDesc(sessionId).orElse(null);
    }

    /** 标记检查点为完成 */
    public void markCompleted(String sessionId) {
        AgentCheckpoint cp = loadLatest(sessionId);
        if (cp != null) {
            cp.setStatus(AgentCheckpoint.CheckpointStatus.COMPLETED);
            checkpointRepository.save(cp);
        }
    }

    /** 标记检查点为暂停（等待人工介入） */
    public void markPaused(String sessionId) {
        AgentCheckpoint cp = loadLatest(sessionId);
        if (cp != null) {
            cp.setStatus(AgentCheckpoint.CheckpointStatus.PAUSED);
            checkpointRepository.save(cp);
        }
    }
}
