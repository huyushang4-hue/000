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
import java.util.concurrent.*;

@Slf4j
@Service
public class AiSdkChatServiceImpl implements AiChatService {

    /** DashScope 多模态对话客户端单例 */
    private final MultiModalConversation conversation = new MultiModalConversation();
    /** 全局会话上下文容器：存储 user/assistant/tool/system 完整消息链，实现多轮记忆 */
    private final List<MultiModalMessage> messages = new ArrayList<>();

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    /** Jackson 序列化器：处理 Token 用量、摘要接口返回 JSON */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 固定地址 https://api.deepseek.com，用于超长对话压缩摘要 */
    private final RestClient deepSeekClient;
    /** 调用 DashScope 兼容 OpenAI 接口做 ASR 语音识别 */
    private final RestClient dashScopeClient;

    private final List<Tool> tools;
    private final List<ToolBase> toolDefs;
    private final PendingMediaQueue mediaQueue;

    /** 最后一次 Token 消耗信息，用于日志监控 */
    private String lastUsage = "";
    /** 上下文最大轮数，超过触发 DeepSeek 摘要压缩 */
    private static final int MAX_ROUNDS = 50;
    /** 压缩后保留最近 10 轮原始对话 */
    private static final int KEEP_ROUNDS = 10;
    /** 单次提问最多工具迭代轮次（模型思考→调工具→再回答循环） */
    private static final int MAX_TOOL_ROUNDS = 10;
    /** 单次请求全局工具最大调用次数，防止死循环 */
    private static final int MAX_TOTAL_CALLS = 20;
    /** 缓存线程池，daemon 线程，命名 tool-exec：同一轮多个工具并行执行，避免串行阻塞大模型循环 */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-exec"); t.setDaemon(true); return t;
    });

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
        // 简单重载，底层全部走带工具调用能力的 doToolLoop，统一入口
        return chatWithTools(userMessage);
    }

    /** 带 Function Calling 工具调用聊天（核心入口）：空值校验 → 给用户消息追加时间戳让模型感知提问时间 → 存入全局 messages → 进入工具调用循环 */
    @Override
    public String chatWithTools(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "请输入想说的话";
        // 给用户消息追加时间戳 [yyyy年M月d日 HH:mm]，让模型感知提问发生时间
        String timedMsg = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + userMessage;
        log.info("FC: {}", timedMsg);
        addUserMsg(timedMsg);

        return doToolLoop(buildParam());
    }

    /** 防抖批量消息处理（图文混合）：前端短时间连发多条消息/图片，后端防抖合并一次送入大模型
     *  - type=IMAGE：解析 base64 图片，按 DashScope 多模态规范构造 content（必须加 data:image/jpeg;base64, 前缀）
     *  - type=TEXT：带时间戳纯文本
     *  最终同样走工具调用循环 */
    @Override
    public String processDebounced(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) return "请输入想说的话";

        log.info("防抖批次 {} 条", entries.size());
        for (Map<String, Object> e : entries) {
            // 取消息类型：TEXT=纯文字，IMAGE=带 Base64 图片
            String type = (String) e.getOrDefault("type", "TEXT");
            String text = (String) e.getOrDefault("text", "");
            if ("IMAGE".equals(type)) {
                String base64 = (String) e.getOrDefault("base64Image", "");
                String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
                List<Map<String, Object>> content = new ArrayList<>();
                // DashScope 多模态入参规范：必须加 data:image/jpeg;base64, 前缀，模型才能识别为图片
                content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64));
                content.add(Collections.singletonMap("text", prompt));
                messages.add(MultiModalMessage.builder().role(Role.USER.getValue()).content(content).build());
            } else {
                String timedMsg = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + text;
                addUserMsg(timedMsg);
            }
        }

        return doToolLoop(buildParam());
    }

    /** 核心：工具调用循环（并行 + 去重 + 上限）
     *  流程：大模型请求 → 判断是否返回 toolCalls
     *    → 有工具调用：去重校验 + 次数限流 → 多工具 CompletableFuture 并行执行
     *      → 工具结果以 role=tool 塞回 messages → 再次请求（下一轮思考）
     *    → 无工具调用：提取最终回答 → 存 assistant 上下文 → Token 统计 → 上下文裁剪 */
    private String doToolLoop(MultiModalConversationParam param) {
        MultiModalConversationResult result = null;
        /** 去重容器：HashSet 记录已执行的"工具名|参数"签名，防止模型反复调用相同工具+参数造成死循环 */
        Set<String> calledSignatures = new HashSet<>();
        int totalCalls = 0;

        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                result = conversation.call(param);
                MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
                List<ToolCallBase> toolCalls = responseMsg.getToolCalls();

                // 无工具调用 = 模型直接给最终答案，跳出循环
                if (toolCalls == null || toolCalls.isEmpty()) break;
                messages.add(responseMsg);

                // 过滤：去重 + 次数限流
                List<ToolCallFunction> validCalls = new ArrayList<>();
                for (ToolCallBase tc : toolCalls) {
                    if (tc instanceof ToolCallFunction tcf) {
                        // 工具签名 = 工具名|参数 JSON，防止重复调用
                        String sig = tcf.getFunction().getName() + "|" + tcf.getFunction().getArguments();
                        if (!calledSignatures.add(sig)) {
                            log.info("跳过重复调用: {}", sig);
                            continue;
                        }
                        if (++totalCalls > MAX_TOTAL_CALLS) {
                            log.warn("已达工具调用上限 {}", MAX_TOTAL_CALLS);
                            break;
                        }
                        validCalls.add(tcf);
                    }
                }

                // CompletableFuture 并行执行：多个不同工具同时跑，不串行排队
                List<CompletableFuture<Void>> futures = validCalls.stream()
                        .map(tcf -> CompletableFuture.runAsync(() -> {
                            ToolCallFunction.CallFunction func = tcf.getFunction();
                            String toolResult = executeTool(func.getName(), func.getArguments());
                            // 多线程写 messages 需加锁，符合 Function Calling 规范回传 toolCallId
                            synchronized (messages) {
                                messages.add(MultiModalMessage.builder()
                                        .role("tool").toolCallId(tcf.getId())
                                        .content(singletonText(toolResult)).build());
                            }
                        }, toolExecutor))
                        .toList();
                // 阻塞等待所有并行工具全部执行完毕，确保下一轮请求能拿到完整数据
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

                param = buildParam();
            }

            // 循环结束后兜底：极端情况返回空，再额外调一次获取回答
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

    /** 工具分发器：Gson 解析模型返回的工具入参 JSON → 遍历 Spring 注入的所有 Tool 实现 → 匹配名称 → 执行业务逻辑 → 未匹配返回兜底 */
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



    // ==================== ASR ====================

    /** 语音转文字 ASR：调用 DashScope OpenAI 兼容接口 qwen3-asr-flash 模型，Base64 编码音频 → 发送 → 取 choices[0].message.content */
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

    /** 解析返回体 usage，兼容两种 key 命名（input_tokens/inputTokens），拼接日志用于计费和用量监控 */
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

    /** 计算实际对话轮数 (messages-1)/2，超 MAX_ROUNDS=50 轮触发压缩：
     *  截取前面老旧历史 → DeepSeek v4-flash 摘要 → 清空 messages →
     *  先放 system 历史摘要 + 再放最近 KEEP_ROUNDS=10 轮原始对话 */

    private void trimContext() {
        int rounds = (messages.size() - 1) / 2;
        if (rounds <= MAX_ROUNDS) return;

        log.info("压缩 {} 轮对话...", rounds);
        int keepStart = messages.size() - KEEP_ROUNDS * 2;

        // 调用 DeepSeek 做老旧历史摘要，300-500 字中文
        String summary = summarize(messages.subList(1, keepStart));
        if (summary == null || summary.isBlank()) return;  // 摘要失败降级：不做压缩，保留原上下文

        List<MultiModalMessage> kept = new ArrayList<>(messages.subList(keepStart, messages.size()));
        messages.clear();
        messages.add(MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(singletonText("【历史摘要】" + summary)).build());
        messages.addAll(kept);
    }

    /** DeepSeek 摘要方法：拼接老旧对话 → 手动 JSON 转义（防反斜杠/双引号破坏 JSON）→ 调 DeepSeek 接口 → 返回摘要文本 */
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
        // 手动 JSON 转义：反斜杠 → \\，双引号 → \"，换行 → \n，防止 JSON 格式崩溃
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