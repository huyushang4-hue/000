package ykd.demo.repository;

import ykd.demo.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    /** 查询待执行的一次性任务（已到期） */
    List<ScheduledTask> findByTypeAndStatusAndExecuteAtBefore(
            ScheduledTask.TaskType type, ScheduledTask.TaskStatus status, LocalDateTime now);

    /** 查询所有待执行的周期任务 */
    List<ScheduledTask> findByTypeAndStatus(ScheduledTask.TaskType type, ScheduledTask.TaskStatus status);

    /** 按用户查询 */
    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(Long userId);
}
