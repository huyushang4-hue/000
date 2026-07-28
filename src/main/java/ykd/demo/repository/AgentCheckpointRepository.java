package ykd.demo.repository;

import ykd.demo.entity.AgentCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpoint, Long> {

    /** 按 sessionId 查找最近的检查点 */
    Optional<AgentCheckpoint> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 按 taskId 查找 */
    Optional<AgentCheckpoint> findByTaskId(Long taskId);
}
