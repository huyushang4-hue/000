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
@Table(name = "conversation", indexes = {
    @Index(name = "idx_user_id", columnList = "userId"),
    @Index(name = "idx_msg_id", columnList = "msgId")
})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联用户 */
    @Column(nullable = false)
    private Long userId;

    /** iLink 消息 ID，用于去重 */
    @Column(length = 128)
    private String msgId;

    /** 角色：USER / ASSISTANT */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 消息类型：TEXT / IMAGE / VOICE / FILE */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private MessageType msgType = MessageType.TEXT;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum MessageRole {
        USER, ASSISTANT
    }

    public enum MessageType {
        TEXT, IMAGE, VOICE, FILE
    }
}
