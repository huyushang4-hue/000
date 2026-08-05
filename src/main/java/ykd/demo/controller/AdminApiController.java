package ykd.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ykd.demo.entity.Conversation;
import ykd.demo.entity.ScheduledTask;
import ykd.demo.entity.User;
import ykd.demo.service.ConversationService;
import ykd.demo.service.TaskSchedulerService;
import ykd.demo.service.UserService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.impl.ReadFileTool;

import java.util.*;

@RestController
@RequiredArgsConstructor
public class AdminApiController {

    private final UserService userService;
    private final ConversationService conversationService;
    private final TaskSchedulerService taskSchedulerService;
    private final AiSdkChatServiceImpl aiChatService;

    /** 进程启动时间，用于计算运行时长 */
    private static final long START_TIME = System.currentTimeMillis();

    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> users() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : userService.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("openId", u.getOpenId());
            m.put("nickname", u.getNickname());
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

    /** 网页控制台聊天：调 AI + 返回本次工具调用轨迹；支持图文（images = [{base64}]） */
    @PostMapping("/api/admin/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        return aiChatService.chatWithToolsWithTrace(message, imagesOf(body.get("images")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> imagesOf(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(o -> o instanceof Map).map(o -> (Map<String, Object>) o).toList();
    }

    /** 上传文件解析文本（PDF/Word/Excel/文本），供网页控制台把文件内容带进对话 */
    @PostMapping(value = "/api/admin/parse-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> parseFile(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        try {
            return Map.of("name", name, "text", ReadFileTool.extractFileText(file.getBytes()));
        } catch (Exception e) {
            return Map.of("name", name, "text", "解析失败：" + e.getMessage());
        }
    }

    /** 定时任务列表 */
    @GetMapping("/api/admin/tasks")
    public List<Map<String, Object>> tasks() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ScheduledTask t : taskSchedulerService.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("type", t.getType().name());
            m.put("status", t.getStatus().name());
            m.put("taskName", t.getTaskName());
            m.put("executeAt", t.getExecuteAt() == null ? null : t.getExecuteAt().toString());
            m.put("cronExpression", t.getCronExpression());
            m.put("result", t.getResult());
            m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
            list.add(m);
        }
        return list;
    }

    /** 系统状态：运行时长 / 内存 / CPU 核数 / 用户数 / 任务数 */
    @GetMapping("/api/admin/status")
    public Map<String, Object> status() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSeconds", (System.currentTimeMillis() - START_TIME) / 1000);
        m.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        m.put("totalMb", rt.totalMemory() / 1024 / 1024);
        m.put("cores", rt.availableProcessors());
        m.put("userCount", userService.findAll().size());
        m.put("taskCount", taskSchedulerService.findAll().size());
        return m;
    }

    /** 网页推送队列：任务推送给无微信用户（网页端创建）的消息，前端轮询拉取（拉取即清除） */
    @GetMapping("/api/admin/pushes")
    public List<String> pushes() {
        List<String> out = new ArrayList<>();
        String m;
        while ((m = TaskSchedulerService.WEB_PUSHES.poll()) != null) out.add(m);
        return out;
    }
}
