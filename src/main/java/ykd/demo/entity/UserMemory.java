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
@Table(name = "user_memory", indexes = {
    @Index(name = "idx_user_category", columnList = "userId,category"),
    @Index(name = "idx_user_key", columnList = "userId,memory_key")
})
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private MemoryCategory category;

    @Column(name = "memory_key", length = 128)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    private MemorySource source = MemorySource.AUTO;

    @Column(nullable = false)
    private Integer confidence = 80;

    @Column(nullable = false)
    private Integer accessCount = 0;

    private LocalDateTime lastAccessedAt;

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

    public enum MemoryCategory {
        FACT, PREFERENCE, HABIT
    }

    public enum MemorySource {
        AUTO, EXPLICIT
    }
}
