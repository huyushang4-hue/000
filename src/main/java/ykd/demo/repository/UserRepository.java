package ykd.demo.repository;

import ykd.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 根据 openId 查找用户 */
    Optional<User> findByOpenId(String openId);

    /** 判断 openId 是否已存在 */
    boolean existsByOpenId(String openId);
}
