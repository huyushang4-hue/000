package ykd.demo.tool.impl;


import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import ykd.demo.entity.UserMemory.MemoryCategory;
import ykd.demo.entity.UserMemory.MemorySource;
import ykd.demo.service.AiChatService;
import ykd.demo.service.MemoryService;
import ykd.demo.tool.Tool;

@Slf4j
@Component
public class MemoryExtractTool implements Tool {

    private final MemoryService memoryService;
    private final AiChatService aiChatService;

    public MemoryExtractTool(MemoryService memoryService, @Autowired @Lazy AiChatService aiChatService) {
        this.memoryService = memoryService;
        this.aiChatService = aiChatService;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("memory_extract",
            "当你从对话中发现值得长期记住的用户信息时调用此工具。" +
            "包括：用户事实（姓名、职业、位置）、用户偏好（喜好、风格）、用户习惯（行为模式）。" +
            "不需要记住普通问答内容，只记住有长期价值的用户画像信息。",
            """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["save","skip"],"description":"save=保存新记忆, skip=无需保存"},
              "category":{"type":"string","enum":["FACT","PREFERENCE","HABIT"],"description":"记忆分类"},
              "key":{"type":"string","description":"记忆键，如'职业'、'饮食偏好'、'作息时间'"},
              "content":{"type":"string","description":"记忆内容，简洁描述"}
            },"required":["action"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String action = args.get("action").getAsString();
        if ("skip".equals(action)) {
            log.info("MemoryExtract: skip");
            return "已跳过，无需保存新记忆";
        }

        String category = args.has("category") ? args.get("category").getAsString() : null;
        String key = args.has("key") ? args.get("key").getAsString() : null;
        String content = args.has("content") ? args.get("content").getAsString() : null;

        if (category == null || key == null || content == null) {
            return "参数不完整，需要 category、key、content";
        }

        try {
            Long userId = aiChatService.getCurrentUserId();
            MemoryCategory memCategory = MemoryCategory.valueOf(category);
            memoryService.saveMemory(userId, memCategory, key, content, MemorySource.AUTO);
            log.info("MemoryExtract: saved userId={}, category={}, key={}, content={}", userId, category, key, content);
            return "记忆已保存";
        } catch (Exception e) {
            log.error("MemoryExtract: 保存失败", e);
            return "记忆保存失败: " + e.getMessage();
        }
    }
}
