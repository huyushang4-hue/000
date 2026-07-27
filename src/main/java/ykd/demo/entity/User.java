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
@Table(name = "robot_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 微信 openId（iLink 的 wxid / 唯一标识） */
    @Column(nullable = false, unique = true, length = 128)
    private String openId;

    /** 微信昵称 */
    @Column(length = 128)
    private String nickname;

    /** 微信头像 URL */
    @Column(length = 512)
    private String avatarUrl;

    /** 用户状态：ACTIVE / BLOCKED */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;

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

    public enum UserStatus {
        ACTIVE, BLOCKED
    }
}
