package ykd.demo.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.config.ResumeContextManager;
import ykd.demo.entity.Conversation;
import ykd.demo.tool.AttachmentStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import ykd.demo.service.TaskSchedulerService;

@Slf4j
@Service
public class ILinkBotService {

    private final AiChatService aiChatService;
    private final AttachmentStore attachmentStore;
    private final ResumeContextManager resumeManager;
    private final UserService userService;
    private final ConversationService conversationService;
    private final ykd.demo.task.TaskOrchestratorService taskOrchestratorService;
    private final ILinkConfig config;
    private static final long DEBOUNCE_MS = 8_000;

    // 多机器人池子：key=自定义机器人唯一标识（如 botKey / wxid）
    private final ConcurrentHashMap<String, BotInstance> botPool = new ConcurrentHashMap<>();
    /** 新 bot 自动编号保证编号不会乱 */
    private final AtomicInteger botCounter = new AtomicInteger(0);

    /**引入一个可以定时的线程池*/
    private final ScheduledExecutorService debouncer = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 2, r -> {
        Thread t = new Thread(r, "debounce");
        t.setDaemon(true);
        return t;
    });

    /** 单个机器人实例包装 */
    private static class BotInstance {
        volatile ILinkClient client;
        final List<DebounceEntry> pending = new ArrayList<>();
        ScheduledFuture<?> debounceTask;
        final String botKey;

        BotInstance(String botKey) { this.botKey = botKey; }

        void close() {
            if (client != null) { client.close(); client = null; }
            if (debounceTask != null && !debounceTask.isDone()) debounceTask.cancel(false);
            pending.clear();
        }
    }

    /** 一条排队中的消息 */
    private static class DebounceEntry {
        String fromUserId;
        String type;
        String text;
        String base64;

        static DebounceEntry text(String uid, String t) {
            DebounceEntry e = new DebounceEntry();
            e.fromUserId = uid; e.type = "TEXT"; e.text = t; return e;
        }
        static DebounceEntry image(String uid, String t, String b64) {
            DebounceEntry e = new DebounceEntry();
            e.fromUserId = uid; e.type = "IMAGE"; e.text = t; e.base64 = b64; return e;
        }
        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("type", type);
            m.put("text", text);
            if (base64 != null) m.put("base64Image", base64);
            return m;
        }
    }

    public ILinkBotService(AiChatService aiChatService, AttachmentStore attachmentStore,
                           ResumeContextManager resumeManager, UserService userService,
                           ConversationService conversationService,
                           @org.springframework.context.annotation.Lazy ykd.demo.task.TaskOrchestratorService taskOrchestratorService) {
        this.aiChatService = aiChatService;
        this.attachmentStore = attachmentStore;
        this.resumeManager = resumeManager;
        this.userService = userService;
        this.conversationService = conversationService;
        this.taskOrchestratorService = taskOrchestratorService;
        this.config = ILinkConfig.builder()
                .heartbeatEnabled(true).heartbeatIntervalMs(5000L).build();
    }

    // ==================== 启停控制 ====================

    /** 启动：恢复所有已保存的 bot，并弹一个新二维码等待新用户扫码 */
    public void start() {
        boolean hasRestored = false;

        // 1. 恢复所有已保存的 bot
        try {
            hasRestored = Files.list(Path.of("sessions")).findAny().isPresent();
            Files.list(Path.of("sessions")).forEach(f -> {
                String key = f.getFileName().toString().replace(".json", "");
                if (!botPool.containsKey(key)) {
                    resumeExisting(key);
                }
            });
        } catch (IOException ignored) {}

        // 2. 没有任何 session → 启动默认 bot 弹出二维码
        if (botPool.isEmpty()) {
            start("default");
            return;  // 不需要 offerNewBot，loginSuccess 里会自动触发
        }

        // 3. 已有恢复的 bot → 额外弹一个二维码给新用户
        if (hasRestored) {
            offerNewBot();
        }
    }

    /** 恢复已有会话（不弹二维码） */
    private void resumeExisting(String botKey) {
        botPool.computeIfAbsent(botKey, BotInstance::new);
        BotInstance bot = botPool.get(botKey);
        try {
            ResumeContext saved = resumeManager.load(botKey);
            if (saved != null) {
                bot.client = ILinkClient.builder()
                        .config(config).resumeContext(saved)
                        .onLogin(createLoginListener(bot))
                        .onDisconnect(createDisconnectListener(bot))
                        .onMessage(msgs -> handleMessages(bot, msgs))
                        .build();
                log.info("[{}] 已恢复会话", botKey);
                // 恢复的 bot 也要追踪编号
                try {
                    int num = Integer.parseInt(botKey.replaceAll("[^0-9]", ""));
                    if (num >= botCounter.get()) botCounter.set(num);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("[{}] 恢复会话失败", botKey, e);
        }
    }

    /** 弹出一个新二维码，自动编号，等待扫码 */
    public void offerNewBot() {
        String key = "bot" + botCounter.incrementAndGet();
        botPool.put(key, new BotInstance(key));
        BotInstance bot = botPool.get(key);
        try {
            bot.client = ILinkClient.builder()
                    .config(config)
                    .onLogin(createLoginListener(bot))
                    .onDisconnect(createDisconnectListener(bot))
                    .onMessage(msgs -> handleMessages(bot, msgs))
                    .build();
            String qr = bot.client.executeLogin();
            log.info("");
            log.info("╔══════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
            log.info("  📱 [{}] 等待新用户扫码...", key);
            log.info("  {}", qr);
            log.info("╚══════════════════════════════════════════════════════════════════════════════════════════════════════════╝");
            log.info("");
        } catch (Exception e) {
            log.error("[{}] 创建二维码失败", key, e);
        }
    }

    /** 启动指定 botKey 的 iLink 机器人（手动调用） */
    public void start(String botKey) {
        botPool.computeIfAbsent(botKey, BotInstance::new);
        BotInstance bot = botPool.get(botKey);

        try {
            ResumeContext saved = resumeManager.load(botKey);
            if (saved != null) {
                bot.client = ILinkClient.builder()
                        .config(config)
                        .resumeContext(saved)
                        .onLogin(createLoginListener(bot))
                        .onDisconnect(createDisconnectListener(bot))
                        .onMessage(msgs -> handleMessages(bot, msgs))
                        .build();
                log.info("[{}] 已恢复会话 botId={}", botKey, saved.getLoginContext().getBotId());
            } else {
                bot.client = ILinkClient.builder()
                        .config(config)
                        .onLogin(createLoginListener(bot))
                        .onDisconnect(createDisconnectListener(bot))
                        .onMessage(msgs -> handleMessages(bot, msgs))
                        .build();
                log.info("[{}] 请扫码登录：{}", botKey, bot.client.executeLogin());
            }
        } catch (Exception e) {
            log.error("[{}] iLink 启动失败", botKey, e);
        }
    }

    /** 停止指定 botKey */
    public void stop(String botKey) {
        BotInstance removed = botPool.remove(botKey);
        if (removed != null) {
            removed.close();
            log.info("[{}] 机器人已停止", botKey);
        }
    }

    /** 停止所有机器人 */
    public void stop() {
        botPool.values().forEach(BotInstance::close);
        botPool.clear();
        debouncer.shutdown();
        log.info("所有 iLink 机器人已停止");
    }

    /** 强制重新登录 */
    public void forceReLogin(String botKey) {
        resumeManager.clear(botKey);
        stop(botKey);
        start(botKey);
    }

    /** 获取运行中的 botKey 列表 */
    public Set<String> getRunningBotKeys() {
        return botPool.keySet();
    }

    /** 获取指定 bot 的 client */
    public ILinkClient getClient(String botKey) {
        BotInstance bot = botPool.get(botKey);
        return bot == null ? null : bot.client;
    }

    // ==================== 回调工厂 ====================

    private OnLoginListener createLoginListener(BotInstance bot) {
        return new OnLoginListener() {
            @Override public void onLoginSuccess(LoginContext ctx) {
                log.info("[{}] 🎉 新用户扫码成功！BotID={}", bot.botKey, ctx.getBotId());
                resumeManager.saveFrom(bot.botKey, ctx);
                // 自动准备下一个二维码
                offerNewBot();
            }
            @Override public void onLoginFailure(Throwable throwable) {
                log.error("[{}] iLink 登录失败", bot.botKey, throwable);
            }
        };
    }

    private OnDisconnectListener createDisconnectListener(BotInstance bot) {
        return new OnDisconnectListener() {
            @Override public void onDisconnect(Throwable cause) {
                log.warn("[{}] 连接断开", bot.botKey);
                bot.client = null;
            }
            @Override public void onReconnectStart(int attempt) {}
            @Override public void onReconnectSuccess() {
                log.info("[{}] 重连成功", bot.botKey);
                bot.client = getClientInternal(bot);
            }
            @Override public void onReconnectFailed(Throwable cause) {
                log.error("[{}] 重连失败", bot.botKey, cause);
            }
        };
    }

    private ILinkClient getClientInternal(BotInstance bot) {
        if (bot.client != null) return bot.client;
        try {
            ResumeContext saved = resumeManager.load(bot.botKey);
            if (saved != null) {
                return ILinkClient.builder()
                        .config(config).resumeContext(saved)
                        .onLogin(createLoginListener(bot))
                        .onDisconnect(createDisconnectListener(bot))
                        .onMessage(msgs -> handleMessages(bot, msgs))
                        .build();
            }
        } catch (Exception e) {
            log.error("[{}] 重建 client 失败", bot.botKey, e);
        }
        return null;
    }

    // ==================== 消息处理 ====================

    //一条一条处理消息
    private void handleMessages(BotInstance bot, List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            try { handleSingleMessage(bot, msg); }
            catch (Exception e) { log.error("[{}] 处理消息失败", bot.botKey, e); }
        }
    }

    private void handleSingleMessage(BotInstance bot, WeixinMessage msg) throws IOException {
        ILinkClient cl = bot.client;
        if (cl == null) return;
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        String text = null;
        MessageItem imageItem = null, voiceItem = null, fileItem = null;
        for (MessageItem item : items) {
            if (item.getType() == 1 && item.getText_item() != null && text == null)
                text = item.getText_item().getText();
            if (item.getType() == 2 && item.getImage_item() != null && imageItem == null)
                imageItem = item;
            if (item.getType() == 3 && voiceItem == null)
                voiceItem = item;
            if (item.getType() == 4 && fileItem == null)
                fileItem = item;
        }

        cl.sendText(fromUserId, "正在思考……");

        // 4. 人工介入：检测是否有暂停任务等待此用户的确认
        String resumeResult = handlePausedTaskIfAny(cl, fromUserId, text);
        if (resumeResult != null) {
            cl.sendText(fromUserId, resumeResult);
            return;
        }

        if (imageItem != null) {
            log.info("[{}] 收到图片消息 [{}]", bot.botKey, fromUserId);
            byte[] imageBytes = cl.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
            attachmentStore.set(base64, prompt);
            synchronized (bot.pending) {
                bot.pending.add(DebounceEntry.image(fromUserId, prompt, base64));
                resetDebounce(bot);
            }
            return;
        }

        if (voiceItem != null) {
            log.info("[{}] 收到语音消息 [{}]", bot.botKey, fromUserId);
            String voiceText = extractVoiceText(cl, voiceItem);
            if (voiceText == null || voiceText.isBlank()) {
                cl.sendText(fromUserId, "抱歉，没听清，请再说一遍");
                return;
            }
            log.info("[{}] 语音识别：{}", bot.botKey, voiceText);
            synchronized (bot.pending) {
                bot.pending.add(DebounceEntry.text(fromUserId, voiceText));
                resetDebounce(bot);
            }
            return;
        }

        if (fileItem != null) {
            log.info("[{}] 收到文件消息 [{}]", bot.botKey, fromUserId);
            String fn = fileItem.getFile_item() != null ? fileItem.getFile_item().getFile_name() : "";

            if (fn.toLowerCase().matches(".*\\.(mp3|wav|ogg|m4a|aac|wma)$")) {
                try {
                    byte[] fileBytes = cl.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        cl.sendText(fromUserId, "文件下载失败"); return;
                    }
                    String voiceText = aiChatService.speechToText(fileBytes);
                    if (voiceText == null || voiceText.isBlank()) {
                        cl.sendText(fromUserId, "无法识别音频"); return;
                    }
                    log.info("[{}] 音频识别：{}", bot.botKey, voiceText);
                    synchronized (bot.pending) {
                        bot.pending.add(DebounceEntry.text(fromUserId, voiceText));
                        resetDebounce(bot);
                    }
                } catch (Exception e) {
                    log.error("[{}] 处理音频文件失败", bot.botKey, e);
                    cl.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            if (fn.toLowerCase().matches(".*\\.(pdf|docx|xlsx|txt|md|csv|html)$")) {
                try {
                    byte[] fileBytes = cl.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        cl.sendText(fromUserId, "文件下载失败"); return;
                    }
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    attachmentStore.set(base64, "读取文件内容");
                    synchronized (bot.pending) {
                        bot.pending.add(DebounceEntry.text(fromUserId,
                            "用户发送了文件：" + fn + "。请用 read_file 工具，path 参数填 **attachment_store** 即可读取此文件内容"));
                        resetDebounce(bot);
                    }
                } catch (Exception e) {
                    log.error("[{}] 处理文档文件失败", bot.botKey, e);
                    cl.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            cl.sendText(fromUserId, "暂不支持此文件类型（支持：音频 mp3/wav + 文档 pdf/docx/xlsx/txt）");
            return;
        }

        if (text == null || text.isBlank()) return;
        log.info("[{}] 收到文字消息 [{}]: {}", bot.botKey, fromUserId, text);

        // 澄清模式：用户在回答 AI 的追问 → 绕过防抖立即处理
        if (taskOrchestratorService.isAwaitingClarification(fromUserId)) {
            String reply = taskOrchestratorService.continueClarification(fromUserId, text);
            if (reply != null) {
                cl.sendText(fromUserId, reply);
                log.info("[{}] 澄清回复 [{}]: {}", bot.botKey, fromUserId, reply);
                ykd.demo.entity.User u = userService.findOrCreate(fromUserId, null, null);
                conversationService.saveIfNotExists(u.getId(), null,
                        Conversation.MessageRole.USER, text, Conversation.MessageType.TEXT);
                conversationService.saveIfNotExists(u.getId(), null,
                        Conversation.MessageRole.ASSISTANT, reply, Conversation.MessageType.TEXT);
                // 如果澄清后产生了多步骤计划，继续执行
                scheduleNextStepsIfNeeded(bot, fromUserId);
            }
            return;
        }

        synchronized (bot.pending) {
            bot.pending.add(DebounceEntry.text(fromUserId, text));
            resetDebounce(bot);
        }
    }

    private String extractVoiceText(ILinkClient cl, MessageItem voiceItem) throws IOException {
        com.github.wechat.ilink.sdk.core.model.VoiceItem vd = voiceItem.getVoice_item();
        if (vd != null && vd.getText() != null && !vd.getText().isBlank()) return vd.getText();
        byte[] vb = cl.downloadVoiceFromMessageItem(voiceItem);
        return aiChatService.speechToText(vb);
    }

    private void resetDebounce(BotInstance bot) {
        if (bot.debounceTask != null) bot.debounceTask.cancel(false);
        bot.debounceTask = debouncer.schedule(() -> flushBatch(bot), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== 防抖批量 & 数据库持久化 ====================

    private void flushBatch(BotInstance bot) {
        List<DebounceEntry> batch;
        synchronized (bot.pending) {
            batch = List.copyOf(bot.pending);
            bot.pending.clear();
            bot.debounceTask = null;
        }
        if (batch.isEmpty()) return;
        ILinkClient cl = bot.client;
        if (cl == null) return;
        try {
            String fromUserId = batch.get(batch.size() - 1).fromUserId;
            List<Map<String, Object>> entries = batch.stream().map(DebounceEntry::toMap).toList();

            String summary = entries.stream()
                    .map(e -> e.getOrDefault("text", "[图片]").toString())
                    .collect(Collectors.joining(" | "));
            log.info("[{}] 防抖合并 {} 条: {}", bot.botKey, batch.size(), summary);

            // === 数据库操作（保留） ===
            ykd.demo.entity.User user = userService.findOrCreate(fromUserId, null, null);
            String userMsgSummary = entries.stream()
                    .map(e -> e.getOrDefault("text", "[图片/媒体]").toString())
                    .collect(Collectors.joining("\n---\n"));
            //存入数据库
            conversationService.saveIfNotExists(user.getId(), null,
                    Conversation.MessageRole.USER, userMsgSummary, Conversation.MessageType.TEXT);

            // 核心 AI 调用
            String reply = aiChatService.processDebounced(fromUserId, entries);

            // 检查是否被 ask_user / create_plan 中断（编排器接管）
            ykd.demo.task.InterruptSignal signal = ykd.demo.task.InterruptSignal.get();
            if (signal != null) {
                reply = taskOrchestratorService.handleSignal(fromUserId, signal, reply);
                ykd.demo.task.InterruptSignal.clear();
            }

            if (reply != null && !reply.isBlank()) {
                cl.sendText(fromUserId, reply);
                log.info("[{}] 回复 [{}]: {}", bot.botKey, fromUserId, reply);
                //存入数据库
                conversationService.saveIfNotExists(user.getId(), null,
                        Conversation.MessageRole.ASSISTANT, reply, Conversation.MessageType.TEXT);
            } else if (reply != null && reply.isBlank()) {
                // terminal 工具无文字，但可能有媒体；保存占位避免历史缺口
                conversationService.saveIfNotExists(user.getId(), null,
                        Conversation.MessageRole.ASSISTANT, "已完成（媒体文件）", Conversation.MessageType.TEXT);
            }
            flushPendingImages(cl, fromUserId);
            flushPendingPushes(cl, user.getId(), fromUserId);
        } catch (Exception e) {
            log.error("[{}] 防抖批次处理失败", bot.botKey, e);
        }
    }

    /** 4. 检查用户是否有暂停任务，有则处理确认/取消 */
    private String handlePausedTaskIfAny(ILinkClient cl, String fromUserId, String text) {        if (text == null || text.isBlank()) return null;
        // 查找该用户的暂停任务
        var paused = ykd.demo.agent.AgentLoopRunner.PAUSED_TASKS;
        for (var entry : paused.entrySet()) {
            var runner = entry.getValue();
            if (runner.getState() != ykd.demo.agent.AgentState.PAUSED) continue;
            // 简单匹配：消息含"确认""继续""取消"等关键词
            String t = text.toLowerCase();
            if (t.contains("确认") || t.contains("继续") || t.contains("好的") ||
                    t.contains("取消") || t.contains("不要") || t.contains("放弃")) {
                return runner.resume(text);
            }
        }
        return null;
    }

    private void flushPendingImages(ILinkClient cl, String toUserId) {
        List<Map.Entry<byte[], String>> media = aiChatService.drainPendingImages();
        for (Map.Entry<byte[], String> entry : media) {
            byte[] data = entry.getKey();
            String fn = entry.getValue();
            long maxSize = fn.endsWith(".wav") || fn.endsWith(".mp3") || fn.endsWith(".silk") ? 15_000_000 : 5_000_000;
            if (data == null || data.length == 0) { log.warn("积压媒体为空，跳过: {}", fn); continue; }
            if (data.length > maxSize) { log.warn("积压媒体过大 ({} KB)，跳过: {}", data.length / 1024, fn); continue; }
            for (int retry = 0; retry < 2; retry++) {
                try {
                    if (fn.endsWith(".wav") || fn.endsWith(".mp3") || fn.endsWith(".silk"))
                        cl.sendFile(toUserId, data, fn, "点击播放");
                    else
                        cl.sendImage(toUserId, data, fn, "");
                    log.info("积压媒体已发送 [{}]: {} ({} KB)", toUserId, fn, data.length / 1024);
                    break;
                } catch (Exception e) {
                    if (retry == 0) {
                        log.warn("发送积压媒体失败，重试一次 [{}]: {}", toUserId, fn);
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    } else {
                        log.error("发送积压媒体失败 [{}]: {}", toUserId, fn, e);
                    }
                }
            }
        }
    }

    private void flushPendingPushes(ILinkClient cl, Long userId, String openId) {
        java.util.List<String> pending = TaskSchedulerService.PENDING_PUSHES.remove(userId);
        if (pending == null || pending.isEmpty()) return;
        log.info("定时任务积压推送 {} 条: [{}]", pending.size(), openId);
        for (String msg : pending) {
            try { cl.sendText(openId, "📬 " + msg); }
            catch (Exception e) { log.warn("积压推送仍失败: {}", e.getMessage()); }
        }
    }

    /** 如果用户有未完成的多步骤任务，在 debouncer 上异步执行下一步 */
    private void scheduleNextStepsIfNeeded(BotInstance bot, String fromUserId) {
        if (!taskOrchestratorService.hasMoreSteps(fromUserId)) return;
        debouncer.schedule(() -> executePendingStep(bot, fromUserId), 1500, TimeUnit.MILLISECONDS);
    }

    /** 执行多步骤任务的下一步，发完后再检查是否还有更多步骤 */
    private void executePendingStep(BotInstance bot, String fromUserId) {
        String reply = taskOrchestratorService.executeNextStep(fromUserId);
        if (reply == null) return;
        ILinkClient cl = bot.client;
        if (cl == null) return;
        try {
            cl.sendText(fromUserId, reply);
            log.info("[{}] 步骤回复 [{}]: {}", bot.botKey, fromUserId, reply);
            ykd.demo.entity.User u = userService.findOrCreate(fromUserId, null, null);
            conversationService.saveIfNotExists(u.getId(), null,
                    Conversation.MessageRole.ASSISTANT, reply, Conversation.MessageType.TEXT);
            flushPendingImages(cl, fromUserId);
        } catch (Exception e) {
            log.error("[{}] 步骤回复失败", bot.botKey, e);
        }
        // 递归调度下一步
        scheduleNextStepsIfNeeded(bot, fromUserId);
    }
}
