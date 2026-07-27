package ykd.demo.repository;

import ykd.demo.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 根据消息 ID 去重查询 */
    Optional<Conversation> findByMsgId(String msgId);

    /** 查询某个用户的最近 N 条对话（用于上下文） */
    List<Conversation> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 查询某个用户的全部对话 */
    List<Conversation> findByUserIdOrderByCreatedAtAsc(Long userId);
}
