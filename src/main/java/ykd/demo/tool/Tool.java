package ykd.demo.tool;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public interface Tool {
    FunctionDefinition getDefinition();
    String execute(JsonObject args);

    /** 是否终端工具：执行后 Agent Loop 必须立即停止，不把控制权还给 LLM */
    default boolean isTerminal() { return false; }

    default FunctionDefinition func(String name, String desc, String jsonSchema) {
        return FunctionDefinition.builder()
                .name(name)
                .description(desc)
                .parameters(new JsonParser().parse(jsonSchema).getAsJsonObject())
                .build();
    }
}