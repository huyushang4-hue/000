package ykd.demo.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import ykd.demo.entity.UserMemory;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    List<UserMemory> findByUserIdOrderByAccessCountDescConfidenceDesc(Long userId);

    List<UserMemory> findByUserIdAndCategory(Long userId, UserMemory.MemoryCategory category);

    Optional<UserMemory> findByUserIdAndCategoryAndKey(Long userId, UserMemory.MemoryCategory category, String key);
}
