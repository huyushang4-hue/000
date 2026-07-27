package ykd.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ykd.demo.entity.Conversation;
import ykd.demo.entity.User;
import ykd.demo.service.ConversationService;
import ykd.demo.service.UserService;

import java.util.*;

@RestController
@RequiredArgsConstructor
public class AdminApiController {

    private final UserService userService;
    private final ConversationService conversationService;

    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> users() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : userService.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("openId", u.getOpenId());
            m.put("status", u.getStatus().name());
            m.put("createdAt", u.getCreatedAt().toString());
            list.add(m);
        }
        return list;
    }

    @GetMapping("/api/admin/conversations")
    public List<Map<String, Object>> conversations(Long userId) {
        List<Conversation> convs;
        if (userId != null) {
            convs = conversationService.getUserHistory(userId);
        } else {
            List<User> users = userService.findAll();
            convs = users.isEmpty() ? List.of()
                    : conversationService.getUserHistory(users.get(0).getId());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Conversation c : convs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("userId", c.getUserId());
            m.put("role", c.getRole().name());
            m.put("content", c.getContent());
            m.put("msgType", c.getMsgType().name());
            m.put("createdAt", c.getCreatedAt().toString());
            list.add(m);
        }
        return list;
    }
}
