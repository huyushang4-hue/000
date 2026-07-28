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
@Table(name = "scheduled_task")
public class ScheduledTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ONCE / CRON */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TaskType type;

    /** 周期性任务的 cron 表达式 */
    @Column(length = 64)
    private String cronExpression;

    /** 一次性任务的执行时间 */
    private LocalDateTime executeAt;

    /** PENDING / RUNNING / COMPLETED / FAILED */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    /** 任务名称/描述 */
    @Column(length = 255)
    private String taskName;

    /** 任务参数（JSON） */
    @Column(columnDefinition = "TEXT")
    private String taskParams;

    /** 关联用户 ID */
    private Long userId;

    /** 执行结果 */
    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum TaskType { ONCE, CRON }
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
