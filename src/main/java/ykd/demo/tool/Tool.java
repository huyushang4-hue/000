package ykd.demo.tool;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public interface Tool {
    FunctionDefinition getDefinition();
    String execute(JsonObject args);

    default FunctionDefinition func(String name, String desc, String jsonSchema) {
        return FunctionDefinition.builder()
                .name(name)
                .description(desc)
                .parameters(new JsonParser().parse(jsonSchema).getAsJsonObject())
                .build();
    }
}