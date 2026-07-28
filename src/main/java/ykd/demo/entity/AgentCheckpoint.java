package ykd.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "agent_checkpoint")
public class AgentCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的定时任务 ID（可选） */
    private Long taskId;

    /** 用户 ID */
    private Long userId;

    /** 会话标识（fromUserId） */
    @Column(length = 128)
    private String sessionId;

    /** 当前步数 */
    private int stepCount;

    /** 最大步数限制 */
    private int maxSteps;

    /** 已用 Token 数 */
    private int tokensUsed;

    /** Token 预算上限 */
    private int tokenBudget;

    /** 状态快照（JSON） */
    @Column(columnDefinition = "TEXT")
    private String stateSnapshot;

    /** RUNNING / PAUSED / COMPLETED / FAILED */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private CheckpointStatus status = CheckpointStatus.RUNNING;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum CheckpointStatus { RUNNING, PAUSED, COMPLETED, FAILED }
}
