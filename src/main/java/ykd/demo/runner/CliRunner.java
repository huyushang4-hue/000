package ykd.demo.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ykd.demo.service.AiChatService;
import ykd.demo.service.ConversationService;
import ykd.demo.service.UserService;
import ykd.demo.service.TaskSchedulerService;
import ykd.demo.entity.User;
import ykd.demo.entity.Conversation;
import ykd.demo.entity.ScheduledTask;

import java.util.List;
import java.util.Scanner;

@Order(2)
@Slf4j
@Component
public class CliRunner implements CommandLineRunner {
    private static final String VERSION = "1.0.0";
    /** 网页控制台端口 */
    @Value("${server.port:8080}")
    private String serverPort;
    private final long startTime;
    private final AiChatService aiChatService;
    private final UserService userService;
    private final ConversationService conversationService;
    private final TaskSchedulerService taskService;

    public CliRunner(AiChatService aiChatService,
                    UserService userService, ConversationService conversationService,
                    TaskSchedulerService taskService) {
        this.startTime = System.currentTimeMillis();
        this.aiChatService = aiChatService;
        this.userService = userService;
        this.conversationService = conversationService;
        this.taskService = taskService;
    }

    @Override
    public void run(String... args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=============================================");
        System.out.println("  🚀 机器人已就绪，控制台地址：http://localhost:" + serverPort + "/admin.html");
        System.out.println("=============================================");
        System.out.println("程序已启动，输入命令（输入 help 查看帮助，输入 exit 退出）");
        while (true) {
            try {
                System.out.print(">");
                String line = scanner.nextLine().trim().toLowerCase();
                if (line.isEmpty()) continue;

                switch (line) {
                    case "help":
                        showHelp();
                        break;
                    case "version":
                        System.out.println("程序版本：" + VERSION);
                        break;
                    case "status":
                        showStatus();
                        break;
                    case "users":
                        showUsers();
                        break;
                    case "history":
                        showHistory();
                        break;
                    case "tasks":
                        showTasks();
                        break;
                    case "test":
                        int x = 1 / 0;
                        break;
                    case "/qwen": {
                        System.out.println("进入聊天模式，输入 exit 退出");
                        while (true) {
                            System.out.print("你> ");
                            String msg = scanner.nextLine().trim();
                            if (msg.equalsIgnoreCase("exit")) break;
                            String result1 = aiChatService.chat(msg);
                            System.out.println("AI> " + result1);
                        }
                        break;
                    }
//                    case "/weather":
//                        System.out.print("请输入城市名：");
//                        String city = scanner.nextLine().trim();
//                        String result = weatherService.getWeather(city);
//                        System.out.println(result);
//                        break;
                    case "exit":
                        System.out.println("再见！");
                        return;
                    default:
                        System.out.println("未知命令，输入 help 查看帮助");
                }
            } catch (Exception e) {
                log.error("命令执行失败", e);
                System.err.println("错误：" + e.getMessage());
            }
        }
    }

    private void showHelp() {
        System.out.println("========== 帮助 ==========");
        System.out.println("help     - 显示此帮助信息");
        System.out.println("version  - 显示程序版本");
        System.out.println("status   - 显示程序运行状态");
        System.out.println("users    - 查看所有用户");
        System.out.println("history  - 查看最近聊天记录");
        System.out.println("tasks    - 查看所有定时任务");
        System.out.println("exit     - 退出程序");
        System.out.println("==========================");
    }

    private void showStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        long totalMB = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMB  = Runtime.getRuntime().freeMemory()  / 1024 / 1024;
        int  cores   = Runtime.getRuntime().availableProcessors();

        System.out.println("=== 运行状态 ===");
        System.out.println("运行时长：" + uptime + " 秒");
        System.out.println("内存使用：" + freeMB + "MB / " + totalMB + "MB");
        System.out.println("CPU 核数：" + cores);
        System.out.println("===============");
    }

    private void showUsers() {
        List<User> users = userService.findAll();
        if (users.isEmpty()) {
            System.out.println("暂无用户");
            return;
        }
        System.out.println("=== 用户列表 (共" + users.size() + "人) ===");
        System.out.printf("%-4s %-40s %-12s %s%n", "ID", "OpenID", "状态", "创建时间");
        for (User u : users) {
            System.out.printf("%-4d %-40s %-12s %s%n",
                    u.getId(), u.getOpenId(), u.getStatus(), u.getCreatedAt());
        }
    }

    private void showHistory() {
        List<User> users = userService.findAll();
        if (users.isEmpty()) {
            System.out.println("暂无用户，没有聊天记录");
            return;
        }
        User first = users.get(0);
        List<Conversation> convs = conversationService.getRecentConversations(first.getId(), 20);
        if (convs.isEmpty()) {
            System.out.println("用户 " + first.getOpenId() + " 暂无聊天记录");
            return;
        }
        List<Conversation> reversed = new java.util.ArrayList<>(convs);
        java.util.Collections.reverse(reversed);
        System.out.println("=== " + first.getOpenId() + " 的最近对话 ===");
        for (Conversation c : reversed) {
            String role = c.getRole() == Conversation.MessageRole.USER ? "🧑" : "🤖";
            String content = c.getContent().length() > 80 ? c.getContent().substring(0, 80) + "..." : c.getContent();
            System.out.println(role + " " + content);
        }
    }

    private void showTasks() {
        List<ScheduledTask> tasks = taskService.findAll();
        if (tasks.isEmpty()) {
            System.out.println("暂无定时任务");
            return;
        }
        System.out.println("=== 定时任务 (共" + tasks.size() + "个) ===");
        System.out.printf("%-4s %-8s %-12s %-20s %s%n", "ID", "类型", "状态", "名称", "执行时间/Cron");
        for (ScheduledTask t : tasks) {
            String time = t.getType() == ScheduledTask.TaskType.ONCE
                    ? (t.getExecuteAt() != null ? t.getExecuteAt().toString() : "-")
                    : (t.getCronExpression() != null ? t.getCronExpression() : "-");
            System.out.printf("%-4d %-8s %-12s %-20s %s%n",
                    t.getId(), t.getType(), t.getStatus(), t.getTaskName(), time);
        }
    }
}
