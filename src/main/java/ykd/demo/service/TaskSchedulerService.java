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
    @Scheduled(fixedDelay = 30_000)
    public void scanCronTasks() {
        List<ScheduledTask> tasks = taskRepository.findByTypeAndStatus(
                ScheduledTask.TaskType.CRON,
                ScheduledTask.TaskStatus.PENDING);

        for (ScheduledTask task : tasks) {
            try {
                // 检查 CRON 表达式是否匹配当前时间
                String cron = task.getCronExpression();
                if (cron == null || cron.isBlank()) continue;
                // Spring CronExpression 需 6 字段，AI 可能给 5 字段（标准 cron），自动补秒
                if (cron.split("\\s+").length == 5) cron = "0 " + cron;
                
                org.springframework.scheduling.support.CronExpression expr =
                        org.springframework.scheduling.support.CronExpression.parse(cron);
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime lastTrigger = expr.next(now.minusSeconds(60));  // 查过去60秒内的触发点
                
                if (lastTrigger == null || lastTrigger.isAfter(now)) continue;  // 还没到时间
                
                log.info("CRON 触发: [{}] {}, expr={}", task.getId(), task.getTaskName(), cron);
                executeTask(task);
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

    /** 执行任务：跑完整 Agent Loop（工具调用 + 自动决策），支持重试和变化检测 */
    private void executeTask(ScheduledTask task) {
        int maxRetries = 2;
        Exception lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                log.info(">>> 执行任务 attempt={}/{}: id={}, name={}",
                        attempt + 1, maxRetries + 1, task.getId(), task.getTaskName());

                // 构建 prompt：包含上次结果用于变化检测
                StringBuilder prompt = new StringBuilder();
                prompt.append("【系统】你是后台定时任务执行器，不是聊天助手。不要反问、不要确认、不要问频率。直接执行任务并输出结果。\n");
                prompt.append("任务：").append(task.getTaskName());
                if (task.getTaskParams() != null && !task.getTaskParams().isBlank()) {
                    prompt.append("\n参数: ").append(task.getTaskParams());
                }
                // 注入上次结果，让 AI 对比变化
                if (task.getResult() != null && !task.getResult().isBlank()) {
                    prompt.append("\n\n上次搜索的结果：\n").append(task.getResult());
                    prompt.append("\n\n请重新搜索并对比：只报告新增或变化的内容。如果和上次一样，回复[暂无变化]。");
                } else {
                    prompt.append("\n\n你可以调用任何需要的工具（搜索、天气等）来完成这个任务。");
                }
                prompt.append("\n完成后直接回复用户，不要创建新的定时任务。");

                String reply = aiChatService.chatWithTools(prompt.toString());

                if (reply == null || reply.isBlank() || reply.contains("AI处理超时")) {
                    throw new RuntimeException("Agent Loop 返回空或超时: " + reply);
                }

                log.info(">>> 任务完成: id={}, reply={}",
                        task.getId(), reply.length() > 100 ? reply.substring(0, 100) + "..." : reply);

                // CRON 任务保持 PENDING 以便下次触发，ONCE 任务标记完成
                task.setResult(reply);
                if (task.getType() == ScheduledTask.TaskType.ONCE) {
                    task.setStatus(ScheduledTask.TaskStatus.COMPLETED);
                }
                // CRON 保持 PENDING，只更新结果
                taskRepository.save(task);

                if (task.getUserId() != null && !reply.contains("暂无变化")) {
                    sendToUser(task.getUserId(), task.getTaskName() + "：\n" + reply);
                } else if (task.getUserId() != null) {
                    log.info("无变化，不推送: id={}", task.getId());
                }
                return;

            } catch (Exception e) {
                lastError = e;
                log.warn("任务执行失败 attempt={}: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                }
            }
        }

        log.error("任务彻底失败: id={}", task.getId(), lastError);
        task.setStatus(ScheduledTask.TaskStatus.FAILED);
        task.setResult("失败: " + (lastError != null ? lastError.getMessage() : "未知错误"));
        taskRepository.save(task);

        if (task.getUserId() != null) {
            sendToUser(task.getUserId(), "⚠️ 任务「" + task.getTaskName() + "」执行失败");
        }
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
