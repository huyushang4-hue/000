package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.entity.User;
import ykd.demo.repository.UserRepository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 根据 openId 查找或自动注册用户。
     * 首次发消息的用户自动入库，status=ACTIVE。
     */
    public User findOrCreate(String openId, String nickname, String avatarUrl) {
        return userRepository.findByOpenId(openId).orElseGet(() -> {
            User newUser = new User();
            newUser.setOpenId(openId);
            newUser.setNickname(nickname != null ? nickname : "微信用户");
            newUser.setAvatarUrl(avatarUrl);
            newUser.setStatus(User.UserStatus.ACTIVE);
            User saved = userRepository.save(newUser);
            log.info("新用户注册: openId={}, id={}", openId, saved.getId());
            return saved;
        });
    }

    /** 按 openId 查找 */
    public Optional<User> findByOpenId(String openId) {
        return userRepository.findByOpenId(openId);
    }

    /** 查询所有用户 */
    public List<User> findAll() {
        return userRepository.findAll();
    }

    /** 封禁用户 */
    public void blockUser(String openId) {
        userRepository.findByOpenId(openId).ifPresent(user -> {
            user.setStatus(User.UserStatus.BLOCKED);
            userRepository.save(user);
        });
    }

    /** 解封用户 */
    public void unblockUser(String openId) {
        userRepository.findByOpenId(openId).ifPresent(user -> {
            user.setStatus(User.UserStatus.ACTIVE);
            userRepository.save(user);
        });
    }
}
