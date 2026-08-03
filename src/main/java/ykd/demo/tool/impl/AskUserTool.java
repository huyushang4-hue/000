package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

/** 向用户提问以澄清/补全信息 —— 多轮对话的关键工具。
 *  当 AI 判断信息不足以完成任务时调用此工具，doToolLoop 检测到后中断循环将问题返回用户。 */
@Slf4j
@Component
public class

AskUserTool implements Tool {

    @Override
    public FunctionDefinition getDefinition() {
        return func("ask_user",
            "当任务需要更多信息才能继续时，向用户提问以澄清或补全信息。调用后任务暂停并等待用户回复。澄清上限3次。",
            """
            {"type":"object","properties":{
              "question":{"type":"string","description":"向用户提出的问题，表述应清晰友好"},
              "missing_fields":{"type":"array","items":{"type":"string"},"description":"还缺少哪些信息字段，如：城市、日期、预算"}
            },"required":["question","missing_fields"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String question = args.get("question").getAsString();
        log.info("Tool: ask_user, question={}", question);
        return question;
    }
}
