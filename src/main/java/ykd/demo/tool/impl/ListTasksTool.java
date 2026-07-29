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
import java.util.stream.Collectors;

/**
 * 列出当前所有定时任务。AI 删除任务前先调用此工具查看列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListTasksTool implements Tool {

    private final ScheduledTaskRepository taskRepository;

    @Override
    public FunctionDefinition getDefinition() {
        return func("list_tasks", "列出所有定时任务（含ID、名称、类型、状态）。删除任务前先调用此工具获取任务ID", """
            {"type":"object","properties":{},"required":[]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        List<ScheduledTask> tasks = taskRepository.findAll();
        if (tasks.isEmpty()) return "当前没有定时任务";
        return tasks.stream()
                .map(t -> String.format("[%d] %s (%s) - %s", t.getId(), t.getTaskName(), t.getType(), t.getStatus()))
                .collect(Collectors.joining("\n"));
    }
}
