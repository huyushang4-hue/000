package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

/** 将复杂任务拆解为多步骤执行计划 —— 多步骤自动推进的关键工具。
 *  AI 调用后 doToolLoop 中断，编排器接管并按计划逐步执行。 */
@Slf4j
@Component
public class CreatePlanTool implements Tool {

    @Override
    public FunctionDefinition getDefinition() {
        return func("create_plan",
            "将一个复杂任务拆解为多步骤执行计划。每步可指定需要调用的工具及参数。调用后系统会逐步执行各步骤。",
            """
            {"type":"object","properties":{
              "goal":{"type":"string","description":"任务的最终目标，一句话描述"},
              "steps":{"type":"array","items":{"type":"object","properties":{
                "index":{"type":"integer","description":"步骤序号，从1开始"},
                "description":{"type":"string","description":"该步骤做什么，给人看"},
                "tool":{"type":"string","description":"要调用的工具名，纯AI思考则填null"},
                "tool_args":{"type":"object","description":"工具参数JSON对象，无工具则填null"}
              },"required":["index","description","tool","tool_args"]}}
            },"required":["goal","steps"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String goal = args.has("goal") ? args.get("goal").getAsString() : "";
        log.info("Tool: create_plan, goal={}", goal);
        // 返回完整 JSON，上层编排器解析 steps
        return args.toString();
    }
}
