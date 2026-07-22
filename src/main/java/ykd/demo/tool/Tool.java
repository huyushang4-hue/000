package ykd.demo.tool;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;

/** Function Calling 工具接口——每个工具一个类，Spring 自动扫描注入 */
public interface Tool {
    /** 工具的定义（名称、描述、参数 JSON Schema） */
    FunctionDefinition getDefinition();

    /** 执行工具，返回文字结果（图片/语音走积压队列） */
    String execute(JsonObject args);
}
