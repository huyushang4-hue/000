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
import ykd.demo.skill.SkillManager;
import ykd.demo.service.RagService;
import ykd.demo.service.ConversationService;
import ykd.demo.service.CheckpointService;
import ykd.demo.entity.Conversation;
import ykd.demo.repository.UserRepository;
import ykd.demo.tool.PendingMediaQueue;
import ykd.demo.agent.ErrorCategory;
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
    /** 多用户会话上下文：key = fromUserId（微信ID），value = 该用户的完整 message 链 */
    private final ConcurrentHashMap<String, List<MultiModalMessage>> userContexts = new ConcurrentHashMap<>();
    /** 当前处理线程的用户 ID，ThreadLocal 避免跨线程污染 */
    private final ThreadLocal<String> currentUser = new ThreadLocal<>();
    /** 对话记录服务：加载历史对话构建初始上下文 */
    private final ConversationService conversationService;
    /** 用户仓库：根据 fromUserId 查找数据库用户 */
    private final UserRepository userRepository;
    /** 检查点服务：Agent Loop 进度保存 */
    private final CheckpointService checkpointService;
    private final RagService ragService;
    private final SkillManager skillManager;

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    /** Jackson 序列化器：处理 Token 用量、摘要接口返回 JSON */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 调用 DashScope 兼容 OpenAI 接口做 ASR 语音识别 */
    private final RestClient dashScopeClient;

    /** 所有实现了 Tool 接口的 Spring Bean，构造时自动注入 */
    private final List<Tool> tools;
    /** 工具定义批量转换：Tool.getDefinition() → SDK 的 ToolFunction，传给大模型让模型自主选择调用 */
    private final List<ToolBase> toolDefs;
    /** 积压媒体队列：工具生成的图片/WAV 暂存此处，发完文字后统一发送 */
    private final PendingMediaQueue mediaQueue;

    /** 最后一次 Token 消耗信息，用于日志监控 */
    private String lastUsage = "";
    /** 单次提问最多工具迭代轮次（模型思考→调工具→再回答循环） */
    private static final int MAX_TOOL_ROUNDS = 10;
    /** 单次请求全局工具最大调用次数，防止死循环 */
    private static final int MAX_TOTAL_CALLS = 20;
    /** 缓存线程池，daemon 线程，命名 tool-exec：同一轮多个工具并行执行，避免串行阻塞大模型循环 */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-exec"); t.setDaemon(true); return t;
    });

    /** 构造器逻辑：注入所有 Tool Bean + 媒体队列 → 初始化两个 RestClient 固定 BaseURL →
     *  批量转换工具定义 Tool.getDefinition() → SDK ToolFunction 存入 toolDefs */
    public AiSdkChatServiceImpl(List<Tool> tools, PendingMediaQueue mediaQueue,
                                ConversationService conversationService, UserRepository userRepository,
                                CheckpointService checkpointService, RagService ragService, SkillManager skillManager) {
        this.tools = tools;
        this.dashScopeClient = RestClient.builder().baseUrl("https://dashscope.aliyuncs.com").build();
        this.toolDefs = tools.stream()
                .map(Tool::getDefinition)
                .map(def -> (ToolBase) ToolFunction.builder().function(def).build())
                .toList();
        this.mediaQueue = mediaQueue;
        this.conversationService = conversationService;
        this.userRepository = userRepository;
        this.checkpointService = checkpointService;
        this.ragService = ragService;
        this.skillManager = skillManager;
    }

    // ==================== 文字聊天 ====================

    @Override
    public String chat(String userMessage) {
        return chatWithTools(userMessage);
    }

    /** 获取当前线程用户的 messages 列表 */
    private List<MultiModalMessage> getMessages() {
        String uid = currentUser.get();
        if (uid == null) uid = "default";
        return userContexts.computeIfAbsent(uid, k -> {
            ArrayList<MultiModalMessage> list = new ArrayList<>();
            // 系统指令：引导 LLM 正确使用工具
            list.add(MultiModalMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(singletonText("""
                        你是智能助手。当且仅当用户要求定时提醒/通知/闹钟时：
                        1. 先调 get_datetime 获取当前时间
                        2. 立即调 schedule_task 创建任务（只调一次）
                        3. 创建成功后直接回复"好的，已帮你创建定时提醒！"，不要立即查询/搜索/播报内容
                        其他场景正常使用对应工具即可。\n"""
                        + skillManager.getSkillDirectory() + """
                        注意：对话开头是从数据库恢复的历史记录，请基于这些记录回答用户。"""))
                    .build());
            return list;
        });
    }

    /** 从数据库加载该用户最近对话作为初始上下文 */
    private void loadHistoryIntoContext(String fromUserId) {
        userRepository.findByOpenId(fromUserId).ifPresent(dbUser -> {
            List<Conversation> history = conversationService.getRecentConversations(dbUser.getId(), 20);
            if (history.isEmpty()) return;
            // 反转顺序：最近的在前面，构建上下文需要从旧到新
            List<Conversation> reversed = new ArrayList<>(history);
            java.util.Collections.reverse(reversed);
            for (Conversation conv : reversed) {
                String role = conv.getRole() == Conversation.MessageRole.USER
                        ? Role.USER.getValue() : Role.ASSISTANT.getValue();
                String prefix = "[历史记录 " + conv.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "] ";
                getMessages().add(MultiModalMessage.builder()
                        .role(role).content(singletonText(prefix + conv.getContent())).build());
            }
            log.info("已加载用户 {} 的 {} 条历史对话作为上下文", fromUserId, history.size());
        });
    }

    /** 带 Function Calling 工具调用聊天（核心入口） */
    @Override
    public String chatWithTools(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "请输入想说的话";
        // 给用户消息追加时间戳 [yyyy年M月d日 HH:mm]，让模型感知提问发生时间
        String timedMsg = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + userMessage;
        log.info("FC: {}", timedMsg);
        addUserMsg(timedMsg);

        return doToolLoop(buildParam());
    }

    /** 防抖批量消息处理（图文混合）：fromUserId 标识用户，ThreadLocal 隔离上下文 */
    @Override
    public String processDebounced(String fromUserId, List<Map<String, Object>> entries) {
        currentUser.set(fromUserId);
        try {
            return processDebouncedInternal(entries);
        } finally {
            currentUser.remove();
        }
    }

    /** 防抖内部实现：图文混合多模态，首次用户加载 DB 历史 */
    private String processDebouncedInternal(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) return "请输入想说的话";

        // 首次访问该用户时，从 DB 加载最近 20 条历史对话作为上下文
        String uid = currentUser.get();
        if (getMessages().size() <= 1 && uid != null) {
            loadHistoryIntoContext(uid);
        }

        log.info("防抖批次 {} 条 (用户: {})", entries.size(), uid);
        String lastUserMsg = null;

        // RAG: 首次对话时检索历史相关上下文
        if (getMessages().size() <= 21 && uid != null) {
            String allText = entries.stream()
                    .map(e -> (String) e.getOrDefault("text", ""))
                    .filter(t -> !t.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
            if (!allText.isBlank()) {
                String ragCtx = ragService.searchContext(uid, allText, 8);
                if (ragCtx != null && !ragCtx.isBlank()) {
                    getMessages().add(MultiModalMessage.builder()
                            .role(Role.SYSTEM.getValue()).content(singletonText(ragCtx)).build());
                }
            }
        }

        for (Map<String, Object> e : entries) {
            String type = (String) e.getOrDefault("type", "TEXT");
            String text = (String) e.getOrDefault("text", "");
            if ("IMAGE".equals(type)) {
                String base64 = (String) e.getOrDefault("base64Image", "");
                String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
                List<Map<String, Object>> content = new ArrayList<>();
                content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64));
                content.add(Collections.singletonMap("text", prompt));
                getMessages().add(MultiModalMessage.builder().role(Role.USER.getValue()).content(content).build());
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
        /** 工具结果缓存：key=签名，value=执行结果，用于去重时直接返回缓存 */
        Map<String, String> resultCache = new HashMap<>();
        int totalCalls = 0;

        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                result = conversation.call(param);
                MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
                List<ToolCallBase> toolCalls = responseMsg.getToolCalls();

                // 无工具调用 = 模型直接给最终答案，跳出循环
                if (toolCalls == null || toolCalls.isEmpty()) break;
                getMessages().add(responseMsg);

                // 过滤：去重 + 次数限流 + 防止重复创建任务
                List<ToolCallFunction> validCalls = new ArrayList<>();
                for (ToolCallBase tc : toolCalls) {
                    if (tc instanceof ToolCallFunction tcf) {
                        String sig = tcf.getFunction().getName() + "|" + tcf.getFunction().getArguments();
                        if (!calledSignatures.add(sig)) {
                            // 去重：直接返回缓存的结果，不让 LLM 卡住
                            String cached = resultCache.getOrDefault(sig, "工具已执行过，请继续下一步");
                            synchronized (getMessages()) {
                                getMessages().add(MultiModalMessage.builder()
                                        .role("tool").toolCallId(tcf.getId())
                                        .content(singletonText(cached)).build());
                            }
                            log.info("去重但返回缓存: {}", sig);
                            continue;
                        }
                        if (++totalCalls > MAX_TOTAL_CALLS) {
                            log.warn("已达工具调用上限 {}", MAX_TOTAL_CALLS);
                            break;
                        }
                        validCalls.add(tcf);
                    }
                }

                if (validCalls.isEmpty()) break;

                // CompletableFuture 并行执行：多个不同工具同时跑，不串行排队
                final String uid = currentUser.get();
                List<CompletableFuture<Void>> futures = validCalls.stream()
                        .map(tcf -> CompletableFuture.runAsync(() -> {
                            currentUser.set(uid);  // 恢复 ThreadLocal 到异步线程
                            try {
                                ToolCallFunction.CallFunction func = tcf.getFunction();
                                String toolResult = executeTool(func.getName(), func.getArguments());
                                String sig = func.getName() + "|" + func.getArguments();
                                resultCache.put(sig, toolResult);
                                synchronized (getMessages()) {
                                    getMessages().add(MultiModalMessage.builder()
                                            .role("tool").toolCallId(tcf.getId())
                                            .content(singletonText(toolResult)).build());
                                }
                            } finally {
                                currentUser.remove();
                            }
                        }, toolExecutor))
                        .toList();
                // 阻塞等待所有并行工具全部执行完毕，确保下一轮请求能拿到完整数据
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

                if (validCalls.stream().anyMatch(tc -> tools.stream().anyMatch(t -> t.getDefinition().getName().equals(tc.getFunction().getName()) && t.isTerminal()))) {
                    return "";
                }

                // 每轮后保存检查点（Agent Loop checkpoint）
                saveCheckpoint(round, totalCalls);

                param = buildParam();
            }

            // 循环结束后兜底：极端情况返回空，再额外调一次获取回答
            String reply = extractText(result);
            if (reply.isBlank()) {
                result = conversation.call(param);
                reply = extractText(result);
            }
            // 最终兜底：从消息历史中找最后一个 assistant 文本
            if (reply.isBlank()) {
                List<MultiModalMessage> msgs = getMessages();
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    MultiModalMessage m = msgs.get(i);
                    if (!"assistant".equals(m.getRole())) continue;  // 只要 AI 回复
                    String text = extractText(m);
                    if (!text.isBlank()) {
                        reply = text;
                        break;
                    }
                }
            }
            if (reply.isBlank()) reply = "AI处理超时，请简化问题重新尝试";
            addAssistantMsg(reply);
            recordUsage(result);

            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            ErrorCategory category = ErrorCategory.classify(e);
            log.error("FC 失败 [{}]: {}", category, e.getMessage());

            return switch (category) {
                case RETRYABLE -> "网络不稳定，正在重试中，请稍候...";
                case AUTH_REQUIRED -> "操作需要授权，请联系管理员处理。";
                case DEGRADABLE -> "操作暂时降级处理，已保存当前进度。";
                case FATAL -> "AI处理超时，请简化问题重新尝试";
            };
        }
    }

    /** 工具分发器：解析参数 → 自动注入当前用户 openId → 执行业务逻辑 */
    private static final int TOOL_RETRY = 2;
    /** 非幂等工具：重试会造成重复副作用（重复建任务/删任务），禁止重试 */
    private static final java.util.Set<String> NO_RETRY_TOOLS =
            java.util.Set.of("schedule_task", "delete_task", "create_task");

    private String executeTool(String name, String arguments) {
        JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
        // 自动注入当前用户 ID
        if ("schedule_task".equals(name) && currentUser.get() != null) {
            args.addProperty("userId", currentUser.get());
        }
        for (Tool tool : tools) {
            if (tool.getDefinition().getName().equals(name)) {
                log.info("Tool: {} -> {}", name, arguments);
                String result = null;
                for (int attempt = 1; attempt <= TOOL_RETRY; attempt++) {
                    try {
                        result = tool.execute(args);
                        if (attempt < TOOL_RETRY && !NO_RETRY_TOOLS.contains(name) && isTransientError(result)) {
                            log.warn("Tool {} 瞬时错误(第{}/{}): {}", name, attempt, TOOL_RETRY, result);
                            sleepQuietly(1500L * attempt);
                            continue;
                        }
                        return result;
                    } catch (Exception e) {
                        log.warn("Tool {} 异常(第{}/{}): {}", name, attempt, TOOL_RETRY, e.getMessage());
                        if (attempt < TOOL_RETRY && !NO_RETRY_TOOLS.contains(name)) {
                            sleepQuietly(1500L * attempt);
                        } else {
                            return "错误：" + e.getMessage();
                        }
                    }
                }
                return result == null ? "错误：工具执行失败" : result;
            }
        }
        log.warn("未知工具: {}", name);
        return "未知工具：" + name;
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** 判断是否为可重试的瞬时错误（网络/超时/服务端临时故障） */
    private boolean isTransientError(String result) {
        if (result == null) return false;
        return result.contains("网络") || result.contains("超时") || result.contains("稍后重试")
                || result.contains("timeout") || result.contains("connection")
                || result.contains("refused") || result.contains("reset");
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

    /** 消费积压媒体队列：取出所有排队未处理的图片/音频，供上层 ILinkBotService 批量发送 */
    @Override
    public List<Map.Entry<byte[], String>> drainPendingImages() {
        return mediaQueue.drain();
    }

    // ==================== 内部工具方法 ====================

    /** singletonText：统一封装 DashScope 要求的 [{"text":"xxx"}] 内容结构 */
    private static List<Map<String, Object>> singletonText(String text) {
        return Collections.singletonList(Collections.singletonMap("text", text));
    }

    /** 快捷添加用户单轮文本消息到上下文 */
    private void addUserMsg(String text) {
        getMessages().add(MultiModalMessage.builder().role(Role.USER.getValue()).content(singletonText(text)).build());
    }

    /** 快捷添加助手单轮文本回复到上下文 */
    private void addAssistantMsg(String text) {
        getMessages().add(MultiModalMessage.builder().role(Role.ASSISTANT.getValue()).content(singletonText(text)).build());
    }

    /** 构建请求参数：固定使用 qwen3.7-plus 模型，携带完整 messages + 全部工具定义，每次工具循环都会重新构建 */
    private MultiModalConversationParam buildParam() {
        return MultiModalConversationParam.builder()
                .apiKey(apiKey).model("qwen3.7-plus")
                .messages(getMessages()).tools(toolDefs).build();
    }

    /** 遍历多模态 content 数组，提取 type=text 的文本内容 */
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

    /** 从 MultiModalMessage 提取文本 */
    private String extractText(MultiModalMessage msg) {
        try {
            List<Map<String, Object>> content = msg.getContent();
            if (content == null) return "";
            for (Map<String, Object> item : content) {
                if (item.containsKey("text")) {
                    Object t = item.get("text");
                    return t != null ? t.toString() : "";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 解析返回体 usage，兼容两种 key 命名 */
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

    /** 保存 Agent Loop 检查点 */
    private void saveCheckpoint(int round, int totalCalls) {
        try {
            String uid = currentUser.get();
            if (uid == null) return;
            userRepository.findByOpenId(uid).ifPresent(dbUser ->
                checkpointService.save(uid, null, dbUser.getId(),
                        round, MAX_TOOL_ROUNDS, totalCalls, 0,
                        "round=" + round + ", calls=" + totalCalls)
            );
        } catch (Exception ignored) {}
    }
}
