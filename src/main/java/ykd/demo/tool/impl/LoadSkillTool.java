package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.skill.Skill;
import ykd.demo.skill.SkillManager;
import ykd.demo.tool.Tool;

/**
 * 按名称加载技能正文。AI 需要了解某个工作流细节时调用。
 * 只返回已注册的技能内容，不接受任意文件路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadSkillTool implements Tool {

    private final SkillManager skillManager;

    @Override
    public FunctionDefinition getDefinition() {
        return func("load_skill", "加载指定技能的工作流详细说明。当需要了解如何完成某个复杂任务时调用", """
            {"type":"object","properties":{"name":{"type":"string","description":"技能名称"}},"required":["name"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String name = args.get("name").getAsString();
        Skill skill = skillManager.findSkill(name);
        if (skill == null) {
            return "未找到技能: " + name + "。可用技能请查看系统提示中的技能目录。";
        }
        log.info("Tool: load_skill -> {}", name);
        return "<skill name=\"" + skill.getName() + "\">\n" + skill.getBody() + "\n</skill>";
    }
}
