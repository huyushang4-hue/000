package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;
import ykd.demo.tool.PendingMediaQueue;

/**
 * 用户可说"压缩一下上下文""对话太长了，精简一下"，AI 调此工具压缩历史对话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompressContextTool implements Tool {

    private final PendingMediaQueue mediaQueue;  // 不实际使用，只为 DI 占位

    @Override
    public FunctionDefinition getDefinition() {
        return func("compress_context", "压缩当前对话上下文，精简历史消息，保留最近几轮完整对话。用户说\"压缩上下文\"\"精简对话\"时调用", """
            {"type":"object","properties":{},"required":[]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        return "上下文压缩指令已发送。系统将精简历史对话，保留核心信息。（实际压缩由 AiSdkChatServiceImpl 执行）";
    }

    @Override
    public boolean isTerminal() { return false; }
}
