package ykd.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ykd.demo.service.AiChatService;
import ykd.demo.service.ILinkBotService;
import ykd.demo.entity.ScheduledTask;
import ykd.demo.entity.User;
import ykd.demo.repository.ScheduledTaskRepository;
import ykd.demo.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class TaskSchedulerService {

    private final ScheduledTaskRepository taskRepository;
    private final AiChatService aiChatService;
    private final ILinkBotService ilinkBotService;
    private final UserRepository userRepository;

    public TaskSchedulerService(ScheduledTaskRepository taskRepository,
                                @Lazy AiChatService aiChatService,
                                @Lazy ILinkBotService ilinkBotService,
                                @Lazy UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.aiChatService = aiChatService;
        this.ilinkBotService = ilinkBotService;
        this.userRepository = userRepository;
    }

    /** 每 10 秒扫描一次，执行到期的一次性任务 */
    @Scheduled(fixedDelay = 10_000)
    public void scanOnceTasks() {
        List<ScheduledTask> tasks = taskRepository.findByTypeAndStatusAndExecuteAtBefore(
                ScheduledTask.TaskType.ONCE,
                ScheduledTask.TaskStatus.PENDING,
                LocalDateTime.now());

        for (ScheduledTask task : tasks) {
            try {
                log.info("执行一次性任务: [{}] {}", task.getId(), task.getTaskName());
                task.setStatus(ScheduledTask.TaskStatus.RUNNING);
                taskRepository.save(task);

                // 实际执行逻辑：根据 taskParams 调用 AI 或其他服务
                executeTask(task);

                task.setStatus(ScheduledTask.TaskStatus.COMPLETED);
                task.setResult("执行成功");
                taskRepository.save(task);
            } catch (Exception e) {
                log.error("任务 [{}] 执行失败", task.getId(), e);
                task.setStatus(ScheduledTask.TaskStatus.FAILED);
                task.setResult("错误: " + e.getMessage());
                taskRepository.save(task);
            }
        }
    }

    /** 每分钟扫描一次，检查周期任务是否需要执行 */
    @Scheduled(fixedDelay = 60_000)
    public void scanCronTasks() {
        List<ScheduledTask> tasks = taskRepository.findByTypeAndStatus(
                ScheduledTask.TaskType.CRON,
                ScheduledTask.TaskStatus.PENDING);

        for (ScheduledTask task : tasks) {
            try {
                // 简化：周期任务每次扫描时执行
                log.info("执行周期任务: [{}] {}", task.getId(), task.getTaskName());
                executeTask(task);
                task.setResult("最近执行成功: " + LocalDateTime.now());
                taskRepository.save(task);
            } catch (Exception e) {
                log.error("周期任务 [{}] 执行失败", task.getId(), e);
            }
        }
    }

    /** 添加一次性任务 */
    public ScheduledTask addOnceTask(String name, String params, Long userId, LocalDateTime executeAt) {
        ScheduledTask task = new ScheduledTask();
        task.setType(ScheduledTask.TaskType.ONCE);
        task.setTaskName(name);
        task.setTaskParams(params);
        task.setUserId(userId);
        task.setExecuteAt(executeAt);
        task.setStatus(ScheduledTask.TaskStatus.PENDING);
        return taskRepository.save(task);
    }

    /** 添加周期任务 */
    public ScheduledTask addCronTask(String name, String params, Long userId, String cronExpression) {
        ScheduledTask task = new ScheduledTask();
        task.setType(ScheduledTask.TaskType.CRON);
        task.setTaskName(name);
        task.setTaskParams(params);
        task.setUserId(userId);
        task.setCronExpression(cronExpression);
        task.setStatus(ScheduledTask.TaskStatus.PENDING);
        return taskRepository.save(task);
    }

    /** 执行任务：调用 AI 处理并发送到微信（不触发工具调用） */
    private void executeTask(ScheduledTask task) {
        String prompt = "以下是一个到期的定时提醒，请直接回复用户：\n" + task.getTaskName();
        if (task.getTaskParams() != null && !task.getTaskParams().isBlank()) {
            prompt += "\n参数: " + task.getTaskParams();
        }
        prompt += "\n(这是定时的自动消息，不需要创建新任务或调任何工具，直接文本回复即可)";
        log.info(">>> 执行任务: id={}", task.getId());
        String reply = aiChatService.chat(prompt);
        log.info(">>> 任务完成: id={}, reply={}", task.getId(),
                reply.length() > 100 ? reply.substring(0, 100) + "..." : reply);

        // 发送到微信（只发给任务关联的用户）
        if (task.getUserId() != null) {
            sendToUser(task.getUserId(), reply);
        }
        // 不再给所有用户发送
    }

    private void sendToUser(Long userId, String reply) {
        userRepository.findById(userId).ifPresent(user -> {
                String openId = user.getOpenId();
                ilinkBotService.getRunningBotKeys().forEach(botKey -> {
                    try {
                        var client = ilinkBotService.getClient(botKey);
                        if (client == null || !client.isLoggedIn()) return;
                        client.sendText(openId, "⏰ " + reply);
                        log.info("任务回复已发送至微信: [{}] {}", botKey, openId);
                        // 发送语音等积压媒体
                        var media = aiChatService.drainPendingImages();
                        for (var m : media) {
                            byte[] data = m.getKey();
                            String fn = m.getValue();
                            if (fn.endsWith(".wav") || fn.endsWith(".mp3"))
                                client.sendFile(openId, data, fn, "点击播放");
                            else
                                client.sendImage(openId, data, fn, "");
                        }
                    } catch (Exception e) {
                        log.error("发送任务回复失败 [{}]", botKey, e);
                    }
                });
            });
    }

    /** 查询所有任务 */
    public List<ScheduledTask> findAll() {
        return taskRepository.findAll();
    }

    /** 删除任务 */
    public void delete(Long id) {
        taskRepository.deleteById(id);
    }
}
