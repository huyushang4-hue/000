package ykd.demo.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.tools.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.service.AiChatService;
import ykd.demo.tool.PendingMediaQueue;
import ykd.demo.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AiSdkChatServiceImpl implements AiChatService {

    private final MultiModalConversation conversation = new MultiModalConversation();
    private final List<MultiModalMessage> messages = new ArrayList<>();

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    private final ObjectMapper mapper = new ObjectMapper();

    private final RestClient deepSeekClient;
    private final RestClient dashScopeClient;

    private final List<Tool> tools;
    private final List<ToolBase> toolDefs;
    private final PendingMediaQueue mediaQueue;

    private String lastUsage = "";
    private static final int MAX_ROUNDS = 50;
    private static final int KEEP_ROUNDS = 10;

    public AiSdkChatServiceImpl(List<Tool> tools, PendingMediaQueue mediaQueue) {
        this.tools = tools;
        this.deepSeekClient = RestClient.builder().baseUrl("https://api.deepseek.com").build();
        this.dashScopeClient = RestClient.builder().baseUrl("https://dashscope.aliyuncs.com").build();
        this.toolDefs = tools.stream()
                .map(Tool::getDefinition)
                .map(def -> (ToolBase) ToolFunction.builder().function(def).build())
                .toList();
        this.mediaQueue = mediaQueue;
    }

    // ==================== 文字聊天 ====================

    @Override
    public String chat(String userMessage) {
        return chatWithTools(userMessage);
    }

    @Override
    public String chatWithTools(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "请输入想说的话";

        String timedMsg = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + userMessage;
        log.info("FC: {}", timedMsg);
        addUserMsg(timedMsg);

        MultiModalConversationParam param = buildParam();
        MultiModalConversationResult result = null;

        try {
            for (int round = 0; round < 5; round++) {
                result = conversation.call(param);
                MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
                List<ToolCallBase> toolCalls = responseMsg.getToolCalls();

                if (toolCalls == null || toolCalls.isEmpty()) break;

                messages.add(responseMsg);
                for (ToolCallBase tc : toolCalls) {
                    if (tc instanceof ToolCallFunction tcf) {
                        ToolCallFunction.CallFunction func = tcf.getFunction();
                        String toolResult = executeTool(func.getName(), func.getArguments());
                        messages.add(MultiModalMessage.builder()
                                .role("tool").toolCallId(tc.getId())
                                .content(singletonText(toolResult)).build());
                    }
                }
                param = buildParam();
            }

            String reply = extractText(result);
            if (reply.isBlank()) {
                result = conversation.call(param);
                reply = extractText(result);
            }
            if (reply.isBlank()) reply = "AI暂时无法回复，请稍后再试";
            addAssistantMsg(reply);
            recordUsage(result);
            trimContext();
            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("FC 失败", e);
            return "AI暂时无法回复，请稍后再试";
        }
    }

    private String executeTool(String name, String arguments) {
        JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
        for (Tool tool : tools) {
            if (tool.getDefinition().getName().equals(name)) {
                log.info("Tool: {} -> {}", name, arguments);
                return tool.execute(args);
            }
        }
        log.warn("未知工具: {}", name);
        return "未知工具：" + name;
    }

    // ==================== 识图 ====================

    @Override
    public String chatWithImageBase64(String text, String base64Image) {
        if (text == null || text.isBlank()) text = "描述一下这张图片";
        log.info("识图: {}", text);

        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64Image));
            content.add(Collections.singletonMap("text", text));

            messages.add(MultiModalMessage.builder().role(Role.USER.getValue()).content(content).build());

            MultiModalConversationResult result = conversation.call(
                    MultiModalConversationParam.builder()
                            .apiKey(apiKey).model("qwen3.7-plus")
                            .messages(messages).tools(toolDefs)
                            .enableSearch(true).build());

            String reply = extractText(result);
            addAssistantMsg(reply);
            recordUsage(result);
            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("识图失败", e);
            return "图片识别失败，请稍后再试";
        }
    }

    // ==================== ASR ====================

    @Override
    public String speechToText(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return "";
        log.info("ASR: {} 字节", audioBytes.length);

        try {
            String b64 = Base64.getEncoder().encodeToString(audioBytes);
            String body = String.format("""
                    {"model":"qwen3-asr-flash","messages":[{"role":"user","content":[{"type":"audio","audio":"data:audio/wav;base64,%s"}]}]}
                    """, b64);

            String json = dashScopeClient.post()
                    .uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);

            JsonNode choices = mapper.readTree(json).path("choices");
            String text = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText() : "";
            log.info("ASR: {}", text);
            return text;

        } catch (Exception e) {
            log.error("ASR 失败", e);
            return "";
        }
    }

    // ==================== 积压媒体 ====================

    @Override
    public List<Map.Entry<byte[], String>> drainPendingImages() {
        return mediaQueue.drain();
    }

    // ==================== 内部工具方法 ====================

    private static List<Map<String, Object>> singletonText(String text) {
        return Collections.singletonList(Collections.singletonMap("text", text));
    }

    private void addUserMsg(String text) {
        messages.add(MultiModalMessage.builder().role(Role.USER.getValue()).content(singletonText(text)).build());
    }

    private void addAssistantMsg(String text) {
        messages.add(MultiModalMessage.builder().role(Role.ASSISTANT.getValue()).content(singletonText(text)).build());
    }

    private MultiModalConversationParam buildParam() {
        return MultiModalConversationParam.builder()
                .apiKey(apiKey).model("qwen3.7-plus")
                .messages(messages).tools(toolDefs).build();
    }

    private String extractText(MultiModalConversationResult result) {
        try {
            if (result == null) return "";
            List<Map<String, Object>> content = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (content == null) return "";
            for (Map<String, Object> item : content) {
                if (item.containsKey("text")) {
                    Object t = item.get("text");
                    return t != null ? t.toString() : "";
                }
            }
        } catch (Exception e) {
            log.warn("extractText 失败", e);
        }
        return "";
    }

    private void recordUsage(MultiModalConversationResult result) {
        try {
            if (result == null) return;
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
            log.warn("Token 提取失败", e);
        }
    }

    // ==================== 上下文压缩 ====================

    private void trimContext() {
        int rounds = (messages.size() - 1) / 2;
        if (rounds <= MAX_ROUNDS) return;

        log.info("压缩 {} 轮对话...", rounds);
        int keepStart = messages.size() - KEEP_ROUNDS * 2;

        String summary = summarize(messages.subList(1, keepStart));
        if (summary == null || summary.isBlank()) return;

        List<MultiModalMessage> kept = new ArrayList<>(messages.subList(keepStart, messages.size()));
        messages.clear();
        messages.add(MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(singletonText("【历史摘要】" + summary)).build());
        messages.addAll(kept);
    }

    private String summarize(List<MultiModalMessage> history) {
        StringBuilder sb = new StringBuilder("请总结以下对话，覆盖各话题要点，保留关键细节，300-500字中文：\n");
        for (MultiModalMessage msg : history) {
            List<Map<String, Object>> c = msg.getContent();
            if (c == null || c.isEmpty()) continue;
            Object t = c.get(0).get("text");
            if (t == null) continue;
            sb.append("user".equals(msg.getRole()) ? "用户" : "AI")
              .append(": ").append(t.toString()).append("\n");
        }
        String body = """
                {"model":"deepseek-v4-flash","messages":[{"role":"user","content":"%s"}]}
                """.formatted(sb.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));
        try {
            String json = deepSeekClient.post().uri("/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(body).retrieve().body(String.class);
            JsonNode choices = mapper.readTree(json).path("choices");
            return choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText().trim() : "";
        } catch (Exception e) {
            log.error("总结失败", e);
            return null;
        }
    }
}