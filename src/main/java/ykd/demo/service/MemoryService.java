package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.entity.UserMemory;
import ykd.demo.entity.UserMemory.MemoryCategory;
import ykd.demo.entity.UserMemory.MemorySource;
import ykd.demo.repository.UserMemoryRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryRepository memoryRepository;

    public List<UserMemory> queryMemories(Long userId) {
        return memoryRepository.findByUserIdOrderByAccessCountDescConfidenceDesc(userId);
    }

    public String buildMemoryPrompt(Long userId) {
        List<UserMemory> memories = queryMemories(userId);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【用户记忆】\n");

        List<UserMemory> facts = memories.stream()
                .filter(m -> m.getCategory() == MemoryCategory.FACT).toList();
        List<UserMemory> prefs = memories.stream()
                .filter(m -> m.getCategory() == MemoryCategory.PREFERENCE).toList();
        List<UserMemory> habits = memories.stream()
                .filter(m -> m.getCategory() == MemoryCategory.HABIT).toList();

        if (!facts.isEmpty()) {
            sb.append("- 事实：");
            sb.append(facts.stream().map(UserMemory::getContent).reduce((a, b) -> a + "；" + b).orElse(""));
            sb.append("\n");
        }
        if (!prefs.isEmpty()) {
            sb.append("- 偏好：");
            sb.append(prefs.stream().map(UserMemory::getContent).reduce((a, b) -> a + "；" + b).orElse(""));
            sb.append("\n");
        }
        if (!habits.isEmpty()) {
            sb.append("- 习惯：");
            sb.append(habits.stream().map(UserMemory::getContent).reduce((a, b) -> a + "；" + b).orElse(""));
            sb.append("\n");
        }

        memories.forEach(m -> {
            m.setAccessCount(m.getAccessCount() + 1);
            m.setLastAccessedAt(LocalDateTime.now());
        });
        memoryRepository.saveAll(memories);

        log.info("已加载用户 {} 的 {} 条记忆", userId, memories.size());
        return sb.toString();
    }

    public UserMemory saveMemory(Long userId, MemoryCategory category, String key, String content, MemorySource source) {
        return memoryRepository.findByUserIdAndCategoryAndKey(userId, category, key)
                .map(existing -> {
                    existing.setContent(content);
                    existing.setConfidence(Math.min(existing.getConfidence() + 10, 100));
                    existing.setSource(source);
                    log.info("更新记忆: userId={}, category={}, key={}", userId, category, key);
                    return memoryRepository.save(existing);
                })
                .orElseGet(() -> {
                    UserMemory memory = new UserMemory();
                    memory.setUserId(userId);
                    memory.setCategory(category);
                    memory.setKey(key);
                    memory.setContent(content);
                    memory.setSource(source);
                    log.info("新增记忆: userId={}, category={}, key={}", userId, category, key);
                    return memoryRepository.save(memory);
                });
    }
}
