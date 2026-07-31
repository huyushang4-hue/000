package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.entity.User;
import ykd.demo.repository.UserRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** 根据 openId 查找或自动注册用户 */
    public User findOrCreate(String openId, String nickname, String avatarUrl) {
        return userRepository.findByOpenId(openId).orElseGet(() -> {
            User newUser = new User();
            newUser.setOpenId(openId);
            newUser.setNickname(nickname != null ? nickname : "微信用户");
            newUser.setAvatarUrl(avatarUrl);
            newUser.setStatus(User.UserStatus.ACTIVE);
            return userRepository.save(newUser);
        });
    }

    /** 查询所有用户 */
    public List<User> findAll() {
        return userRepository.findAll();
    }
}
