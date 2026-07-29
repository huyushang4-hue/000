package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.entity.ScheduledTask;
import ykd.demo.repository.ScheduledTaskRepository;
import ykd.demo.tool.Tool;

import java.util.List;

/**
 * 取消/删除定时任务工具。用户说"取消那个推送消息的任务"时调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteTaskTool implements Tool {

    private final ScheduledTaskRepository taskRepository;

    @Override
    public FunctionDefinition getDefinition() {
        return func("delete_task", "取消/删除一个定时任务。先调用 list_tasks 查看任务列表，再调用此工具删除指定任务", """
            {"type":"object","properties":{"taskId":{"type":"number","description":"要删除的任务ID，从 list_tasks 结果中获取"}},"required":["taskId"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        try {
            long taskId = args.get("taskId").getAsLong();
            taskRepository.findById(taskId).ifPresentOrElse(task -> {
                taskRepository.delete(task);
                log.info("任务已删除: [{}] {}", taskId, task.getTaskName());
            }, () -> log.warn("任务不存在: {}", taskId));
            return "任务 " + taskId + " 已取消";
        } catch (Exception e) {
            log.error("删除任务失败", e);
            return "取消失败: " + e.getMessage();
        }
    }
}
