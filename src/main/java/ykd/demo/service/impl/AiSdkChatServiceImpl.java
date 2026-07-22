package ykd.demo.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.*;
import com.alibaba.dashscope.tools.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import ykd.demo.service.AiChatService;
import ykd.demo.tool.PendingMediaQueue;
import ykd.demo.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AiSdkChatServiceImpl implements AiChatService {

    private final MultiModalConversation conversation;
    private List<MultiModalMessage> messages;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient deepSeekClient;
    private final List<Tool> tools;
    private final PendingMediaQueue mediaQueue;

    private String lastUsage = "";
    private static final int MAX_ROUNDS = 50;
    private static final int KEEP_ROUNDS = 10;

    public AiSdkChatServiceImpl(List<Tool> tools, PendingMediaQueue mediaQueue) {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        this.conversation = new MultiModalConversation();
        this.messages = new ArrayList<>();
        this.deepSeekClient = RestClient.builder().baseUrl("https://api.deepseek.com").build();
        this.tools = tools;
        this.mediaQueue = mediaQueue;
    }

    // ===== 积压媒体 =====
    @Override
    public List<Map.Entry<byte[], String>> drainPendingImages() {
        return mediaQueue.drain();
    }

    // ===== Function Calling 主循环 =====
    @Override
    public String chatWithTools(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "请输入想说的话";
        String timedMessage = "[" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + userMessage;
        log.info("Function Calling chat, userMessage={}", userMessage);
        try {
            messages.add(MultiModalMessage.builder().role(Role.USER.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", timedMessage))).build());
            List<ToolBase> toolDefs = tools.stream().map(Tool::getDefinition)
                    .map(def -> (ToolBase) ToolFunction.builder().function(def).build()).toList();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages).tools(toolDefs).build();
            for (int round = 0; round < 5; round++) {
                MultiModalConversationResult result = conversation.call(param);
                MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
                List<ToolCallBase> toolCalls = responseMsg.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String reply = extractText(result);
                    messages.add(MultiModalMessage.builder().role(Role.ASSISTANT.getValue())
                            .content(Collections.singletonList(Collections.singletonMap("text", reply))).build());
                    recordUsage(result);
                    trimContext();
                    return reply;
                }
                messages.add(responseMsg);
                for (ToolCallBase tc : toolCalls) {
                    if (tc instanceof ToolCallFunction tcf) {
                        String tr = executeToolByName(tcf.getFunction());
                        messages.add(MultiModalMessage.builder().role("tool").toolCallId(tc.getId())
                                .content(Collections.singletonList(Collections.singletonMap("text", tr))).build());
                    }
                }
                param = MultiModalConversationParam.builder()
                        .apiKey(apiKey).model("qwen3.7-plus").messages(messages).tools(toolDefs).build();
            }
            log.warn("Exceeded max rounds");
            return fallbackText(param);
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("Function Calling failed", e);
            return "AI暂时无法回复";
        }
    }

    private String fallbackText(MultiModalConversationParam param) {
        try {
            MultiModalConversationResult r = conversation.call(param);
            String reply = extractText(r);
            messages.add(MultiModalMessage.builder().role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply))).build());
            recordUsage(r);
            return reply.isBlank() ? "AI暂时无法回复" : reply;
        } catch (Exception e) {
            return "AI暂时无法回复";
        }
    }

    private String executeToolByName(ToolCallFunction.CallFunction func) {
        String name = func.getName();
        JsonObject args = JsonParser.parseString(func.getArguments()).getAsJsonObject();
        for (Tool tool : tools) {
            if (tool.getDefinition().getName().equals(name)) {
                log.info("Tool: {}", name);
                return tool.execute(args);
            }
        }
        log.warn("Unknown tool: {}", name);
        return "未知工具：" + name;
    }

    // ===== 文字聊天 =====
    @Override
    public String chat(String userMessage) {
        return chatWithTools(userMessage);
    }

    // ===== 识图 =====
    @Override
    public String chatWithImageBase64(String text, String base64Image) {
        if (text == null || text.isBlank()) text = "描述这张图片";
        log.info("Image with base64, text={}", text);
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64Image));
            content.add(Collections.singletonMap("text", text));
            messages.add(MultiModalMessage.builder().role(Role.USER.getValue()).content(content).build());
            List<ToolBase> toolDefs = tools.stream().map(Tool::getDefinition)
                    .map(def -> (ToolBase) ToolFunction.builder().function(def).build()).toList();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages)
                    .tools(toolDefs).enableSearch(true).build();
            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);
            recordUsage(result);
            messages.add(MultiModalMessage.builder().role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply))).build());
            return reply;
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("Image failed", e);
            return "图片识别失败";
        }
    }

    // ===== ASR =====
    @Override
    public String speechToText(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return "";
        log.info("ASR, size={}", audioBytes.length);
        try {
            String b64 = Base64.getEncoder().encodeToString(audioBytes);
            String body = String.format("""
                    {"model":"qwen3-asr-flash","messages":[{"role":"user","content":[{"type":"audio","audio":"data:audio/wav;base64,%s"}]}]}
                    """, b64);
            String json = RestClient.builder().baseUrl("https://dashscope.aliyuncs.com").build()
                    .post().uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);
            return mapper.readTree(json).path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("ASR failed", e);
            return "";
        }
    }

    // ===== 内部工具方法 =====

    private String extractText(MultiModalConversationResult result) {
        try {
            List<Map<String, Object>> contentList = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (contentList == null) return "";
            for (Map<String, Object> item : contentList) {
                if (item.containsKey("text")) {
                    Object t = item.get("text");
                    return t != null ? t.toString() : "";
                }
            }
        } catch (Exception e) {
            log.warn("extractText failed", e);
        }
        return "";
    }

    private void recordUsage(MultiModalConversationResult result) {
        try {
            String json = mapper.writeValueAsString(result.getUsage());
            JsonNode u = mapper.readTree(json);
            int i = u.path("input_tokens").asInt(0);
            if (i == 0) i = u.path("inputTokens").asInt(0);
            int o = u.path("output_tokens").asInt(0);
            if (o == 0) o = u.path("outputTokens").asInt(0);
            int t = u.path("total_tokens").asInt(0);
            if (t == 0) t = u.path("totalTokens").asInt(0);
            lastUsage = String.format("📊 Token | input %d | output %d | total %d", i, o, t);
            log.info("Token: {}", lastUsage);
        } catch (Exception e) {
            log.warn("Token extraction failed", e);
        }
    }

    private void trimContext() {
        int rounds = (messages.size() - 1) / 2;
        if (rounds <= MAX_ROUNDS) return;
        log.info("Context reached {} rounds, compressing...", rounds);
        int keepStart = messages.size() - KEEP_ROUNDS * 2;
        List<MultiModalMessage> toSummarize = messages.subList(1, keepStart);
        List<MultiModalMessage> toKeep = messages.subList(keepStart, messages.size());
        String summary = summarize(toSummarize);
        if (summary == null || summary.isBlank()) return;
        List<MultiModalMessage> fresh = new ArrayList<>();
        fresh.add(messages.get(0));
        fresh.add(MultiModalMessage.builder().role(Role.SYSTEM.getValue())
                .content(Collections.singletonList(Collections.singletonMap("text", "【历史摘要】" + summary))).build());
        fresh.addAll(toKeep);
        this.messages = fresh;
        log.info("Compressed {} -> {} messages", messages.size(), fresh.size());
    }

    private String summarize(List<MultiModalMessage> history) {
        StringBuilder sb = new StringBuilder("总结以下对话，覆盖各话题要点，保留关键细节，用中文，300-500字：\n");
        for (MultiModalMessage msg : history) {
            List<Map<String, Object>> c = msg.getContent();
            if (c == null || c.isEmpty()) continue;
            Object t = c.get(0).get("text");
            if (t == null) continue;
            String txt = t.toString();
            if (txt.isBlank()) continue;
            sb.append("user".equals(msg.getRole()) ? "用户" : "AI").append(": ").append(txt).append("\n");
        }
        String body = String.format("{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}",
                sb.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));
        try {
            String json = deepSeekClient.post().uri("/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(body).retrieve().body(String.class);
            return mapper.readTree(json).path("choices").get(0).path("message").path("content").asText().trim();
        } catch (Exception e) {
            log.error("Summarize failed", e);
            return null;
        }
    }
}
