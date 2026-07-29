package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.entity.User;
import ykd.demo.repository.UserRepository;
import ykd.demo.service.TaskSchedulerService;
import ykd.demo.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 自然语言创建定时任务工具。
 * 用户说[每天早上8点发天气]，LLM 提取参数后调用此工具写入数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleTaskTool implements Tool {

    private final TaskSchedulerService taskService;
    private final UserRepository userRepository;


    public FunctionDefinition getDefinition() {
        return func("schedule_task", """
            创建定时任务/提醒/闹钟。当用户要求"X分钟后提醒我"、"明天早上通知我"、"每天8点发消息"等，
            你应先调用 get_datetime 获取当前时间，然后调用此工具创建任务。
            taskType: ONCE(一次性)或CRON(周期性)
            taskName: 任务名称如"提醒睡觉"
            taskParams: JSON参数如{"message":"到点啦"}
            cronExpression: CRON表达式(仅CRON)
            executeTime: 执行时间 yyyy-MM-dd HH:mm(仅ONCE)
            """, """
            {"type":"object","properties":{
              "taskType":{"type":"string","description":"ONCE 或 CRON"},
              "taskName":{"type":"string","description":"任务名称"},
              "taskParams":{"type":"string","description":"任务参数JSON"},
              "cronExpression":{"type":"string","description":"CRON表达式"},
              "executeTime":{"type":"string","description":"执行时间 yyyy-MM-dd HH:mm"}
            },"required":["taskType","taskName"]}
            """);
    }



    public boolean isTerminal() { return true; }

    public String execute(JsonObject args) {
        try {
            String taskType = args.get("taskType").getAsString();
            String taskName = args.get("taskName").getAsString();
            String taskParams = args.has("taskParams") ? args.get("taskParams").getAsString() : "{}";
            String openId = args.has("userId") && !args.get("userId").getAsString().contains("placeholder")
                    ? args.get("userId").getAsString() : null;

            Long dbUserId = openId != null ? userRepository.findByOpenId(openId).map(User::getId).orElse(null) : null;

            if ("CRON".equalsIgnoreCase(taskType)) {
                String cron = args.has("cronExpression") ? args.get("cronExpression").getAsString() : "0 0 8 * * ?";
                taskService.addCronTask(taskName, taskParams, dbUserId, cron);
                log.info("周期任务创建: {}, cron={}", taskName, cron);
                return "周期任务已创建: " + taskName + " (cron: " + cron + ")";
            } else {
                String timeStr = args.has("executeTime") ? args.get("executeTime").getAsString()
                        : LocalDateTime.now().plusMinutes(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                LocalDateTime executeAt = LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                taskService.addOnceTask(taskName, taskParams, dbUserId, executeAt);
                log.info("一次性任务创建: {}, time={}", taskName, executeAt);
                return "一次性任务已创建: " + taskName + " (执行时间: " + timeStr + ")";
            }
        } catch (Exception e) {
            log.error("ScheduleTaskTool 失败", e);
            return "创建任务失败: " + e.getMessage();
        }
    }
}
