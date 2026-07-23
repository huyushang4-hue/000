package ykd.demo.tool.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.AttachmentStore;
import ykd.demo.tool.Tool;

import java.util.*;

@Slf4j
@Component
public class RecognizeImageTool implements Tool {

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    private final AttachmentStore attachmentStore;
    private final MultiModalConversation visionConv = new MultiModalConversation();

    public RecognizeImageTool(AttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("recognize_image", "识别图片内容。当用户发图片且模型需要分析图中内容时调用", """
            {"type":"object","properties":{"query":{"type":"string","description":"想从图片中了解什么，如：图中是什么角色、这是什么场景等"}},"required":["query"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : attachmentStore.getPrompt();
        String base64 = attachmentStore.getBase64();
        if (base64 == null || base64.isBlank()) return "没有待识别的图片";

        log.info("Tool: recognize_image, query={}", query);
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64));
            content.add(Collections.singletonMap("text", query));

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue()).content(content).build();

            MultiModalConversationResult result = visionConv.call(
                    MultiModalConversationParam.builder()
                            .apiKey(apiKey).model("qwen3.7-plus")
                            .messages(Collections.singletonList(userMsg)).build());

            List<Map<String, Object>> out = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (out != null) {
                for (Map<String, Object> item : out) {
                    if (item.containsKey("text")) return item.get("text").toString();
                }
            }
            return "无法识别图片内容";
        } catch (Exception e) {
            log.error("recognize_image failed", e);
            return "图片识别失败";
        }
    }
}