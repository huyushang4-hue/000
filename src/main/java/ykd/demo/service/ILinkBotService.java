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
import ykd.demo.tool.AttachmentStore;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ILinkBotService {

    /** volatile：多线程可见性，防止指令重排。非 final：启动/重登/恢复会话时需重新 build 客户端实例 */
    private volatile ILinkClient client;
    /** 接口依赖注入：上层 AI 大模型服务，所有 AI 能力都委托给它 */
    private final AiChatService aiChatService;
    /** 附件临时存储器：图片/文件 Base64 字符串临时缓存，给工具 read_file/recognize_image 读取 */
    private final AttachmentStore attachmentStore;
    /** 会话持久化管理器：登录成功后保存凭证，下次启动免扫码恢复微信会话 */
    private final ResumeContextManager resumeManager;
    /** iLink SDK 客户端配置：心跳、重连等参数 */
    private final ILinkConfig config;

    /** 消息排队缓冲区：用户 10 秒内连发多条文字/图片/语音，全部先放进此列表等待合并 */
    private final List<DebounceEntry> pending = new ArrayList<>();
    /** 定时任务句柄：用于取消上一次未到期的防抖倒计时，实现滚动防抖（每条新消息重置 10 秒） */
    private ScheduledFuture<?> debounceTask;
    /** 防抖窗口：10000 毫秒 = 10 秒 */
    private static final long DEBOUNCE_MS = 10_000;
    /** 防抖专用单线程定时线程池：守护线程，JVM 退出自动回收；只做一件事：延迟 10 秒后执行 flushBatch() */
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "debounce");
        t.setDaemon(true);
        return t;
    });

    /** 一条排队中的消息，可以是文字或图片 */
    private static class DebounceEntry {
        String fromUserId;
        String type;       // "TEXT" | "IMAGE"
        String text;       // TEXT: 消息文字; IMAGE: 附带文字
        String base64;     // IMAGE / 文档 专用

        static DebounceEntry text(String uid, String t) {
            DebounceEntry e = new DebounceEntry();
            e.fromUserId = uid; e.type = "TEXT"; e.text = t; return e;
        }
        static DebounceEntry image(String uid, String t, String b64) {
            DebounceEntry e = new DebounceEntry();
            e.fromUserId = uid; e.type = "IMAGE"; e.text = t; e.base64 = b64; return e;
        }
        /** toMap() 转换为 Map，传给 processDebounced */
        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("type", type);
            m.put("text", text);
            if (base64 != null) m.put("base64Image", base64);
            return m;
        }
    }

    /** Spring 构造器注入三个依赖 Bean，初始化 iLink 配置：开启心跳，间隔 5 秒维持微信长连接 */
    public ILinkBotService(AiChatService aiChatService, AttachmentStore attachmentStore,
                           ResumeContextManager resumeManager) {
        this.aiChatService = aiChatService;
        this.attachmentStore = attachmentStore;
        this.resumeManager = resumeManager;
        this.config = ILinkConfig.builder()
                .heartbeatEnabled(true).heartbeatIntervalMs(5000L).build();
    }

    /** 启动 iLink：优先从 sessions/default.json 恢复已有会话，没有则扫码登录 */
    public void start() {
        try {
            // 从持久化存储读取上次登录保存的续登上下文
            ResumeContext saved = resumeManager.load();
            if (saved != null) {
                // 有旧 session → 构建带 resumeContext 的客户端，免扫码直接恢复
                client = ILinkClient.builder()
                        .config(config)
                        .resumeContext(saved)
                        .onLogin(createLoginListener())
                        .onDisconnect(createDisconnectListener())
                        .onMessage(this::handleMessages)
                        .build();
                log.info("已恢复会话 botId={}", saved.getLoginContext().getBotId());
            } else {
                // 无旧 session → 正常构建客户端，出二维码
                client = ILinkClient.builder()
                        .config(config)
                        .onLogin(createLoginListener())
                        .onDisconnect(createDisconnectListener())
                        .onMessage(this::handleMessages)
                        .build();
                log.info("请扫码登录：{}", client.executeLogin());
            }
        } catch (Exception e) {
            log.error("iLink 启动失败", e);
        }
    }

    /** 关闭防抖线程池 + 释放 iLink 客户端连接 */
    public void stop() {
        debouncer.shutdown();
        if (client != null) client.close();
    }

    // ==================== 回调工厂 ====================

    /** 登录结果回调：成功 → 持久化会话上下文；失败 → 打印异常日志 */
    private OnLoginListener createLoginListener() {
        return new OnLoginListener() {
            @Override public void onLoginSuccess(LoginContext ctx) {
                log.info("登录成功！BotID={}", ctx.getBotId());
                resumeManager.saveFrom(ctx);
            }
            @Override public void onLoginFailure(Throwable throwable) {
                log.error("iLink 登录失败", throwable);
            }
        };
    }

    /** 断连/重连监听：只做日志告警，重连逻辑由 SDK 内部自动完成 */
    private OnDisconnectListener createDisconnectListener() {
        return new OnDisconnectListener() {
            @Override public void onDisconnect(Throwable cause) { log.warn("iLink 连接断开"); }
            @Override public void onReconnectStart(int attempt) {}
            @Override public void onReconnectSuccess() {}
            @Override public void onReconnectFailed(Throwable cause) {}
        };
    }

    /** 批量消息外层分发：SDK 推送一批微信消息，循环逐条处理，单条异常不影响整批 */
    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            try { handleSingleMessage(msg); }
            catch (Exception e) { log.error("处理消息失败", e); }
        }
    }

    /** 单条消息核心处理：1=文本、2=图片、3=语音、4=文件 */
    private void handleSingleMessage(WeixinMessage msg) throws IOException {
        // volatile 赋值给局部变量，避免循环多次读取主存，线程安全优化
        ILinkClient cl = client;
        if (cl == null) return;
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        // 解析 items：1=文本、2=图片、3=语音、4=文件，只取第一条对应类型
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

        // 图片 → SDK 下载图片二进制 → 转 Base64 → 存入 AttachmentStore（给工具读取）→ 推入防抖队列
        if (imageItem != null) {
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = cl.downloadImageFromMessageItem(imageItem); // SDK 下载图片二进制
            String base64 = Base64.getEncoder().encodeToString(imageBytes);  // 转 Base64
            String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
            attachmentStore.set(base64, prompt);  // 存入附件存储，给 read_file/recognize_image 工具使用
            synchronized (pending) {
                pending.add(DebounceEntry.image(fromUserId, prompt, base64));
                resetDebounce();
            }
            return;
        }

        // 语音 → 优先用微信自带转写，否则下载语音调 DashScope ASR → 入防抖队列
        if (voiceItem != null) {
            log.info("收到语音消息 [{}]", fromUserId);
            String voiceText = extractVoiceText(cl, voiceItem);  // 内部方法：先取 SDK 自带识别，其次调 ASR
            if (voiceText == null || voiceText.isBlank()) {
                cl.sendText(fromUserId, "抱歉，没听清，请再说一遍");
                return;
            }
            log.info("语音识别：{}", voiceText);
            synchronized (pending) {
                pending.add(DebounceEntry.text(fromUserId, voiceText));
                resetDebounce();
            }
            return;
        }

        // 文件处理
        if (fileItem != null) {
            log.info("收到文件消息 [{}]", fromUserId);
            String fn = fileItem.getFile_item() != null ? fileItem.getFile_item().getFile_name() : "";

            // 音频文件（mp3/wav/ogg/m4a/aac/wma）→ ASR → 排队
            if (fn.toLowerCase().matches(".*\\.(mp3|wav|ogg|m4a|aac|wma)$")) {
                try {
                    byte[] fileBytes = cl.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        cl.sendText(fromUserId, "文件下载失败"); return;
                    }
                    // 调用 AiChatService ASR 语音转文字
                    String voiceText = aiChatService.speechToText(fileBytes);
                    if (voiceText == null || voiceText.isBlank()) {
                        cl.sendText(fromUserId, "无法识别音频"); return;
                    }
                    log.info("音频识别：{}", voiceText);
                    synchronized (pending) {
                        pending.add(DebounceEntry.text(fromUserId, voiceText));
                        resetDebounce();
                    }
                } catch (Exception e) {
                    log.error("处理音频文件失败", e);
                    cl.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            // 文档（PDF/Word/Excel/文本）→ 下载 → 暂存 AttachmentStore → 给 AI 插入指令引导调用 read_file 工具
            if (fn.toLowerCase().matches(".*\\.(pdf|docx|xlsx|txt|md|csv|html)$")) {
                try {
                    byte[] fileBytes = cl.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        cl.sendText(fromUserId, "文件下载失败"); return;
                    }
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    attachmentStore.set(base64, "读取文件内容");
                    synchronized (pending) {
                        pending.add(DebounceEntry.text(fromUserId,
                            "用户发送了文件：" + fn + "。请用 read_file 工具，path 参数填 **attachment_store** 即可读取此文件内容"));
                        resetDebounce();
                    }
                } catch (Exception e) {
                    log.error("处理文档文件失败", e);
                    cl.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            cl.sendText(fromUserId, "暂不支持此文件类型（支持：音频 mp3/wav + 文档 pdf/docx/xlsx/txt）");
            return;
        }

        // 纯文字 → 排队
        if (text == null || text.isBlank()) return;
        log.info("收到文字消息 [{}]: {}", fromUserId, text);
        synchronized (pending) {
            pending.add(DebounceEntry.text(fromUserId, text));
            resetDebounce();
        }
    }

    /** 提取语音文字：优先 SDK 自带识别，没有则下载语音二进制调 AiChatService ASR 接口（DashScope） */
    private String extractVoiceText(ILinkClient cl, MessageItem voiceItem) throws IOException {
        com.github.wechat.ilink.sdk.core.model.VoiceItem vd = voiceItem.getVoice_item();
        // 优先使用微信自带转写文字
        if (vd != null && vd.getText() != null && !vd.getText().isBlank()) return vd.getText();
        // 否则下载语音 → 调 DashScope ASR
        byte[] vb = cl.downloadVoiceFromMessageItem(voiceItem);
        return aiChatService.speechToText(vb);
    }

    /** 滚动防抖重置：取消旧定时 → 重新开启 10 秒。效果：用户持续发消息计时器不断刷新，停止 10 秒后才批量执行 AI 请求 */
    private void resetDebounce() {
        if (debounceTask != null) debounceTask.cancel(false);
        debounceTask = debouncer.schedule(this::flushBatch, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** 防抖到期批量刷盘：快照拷贝避免并发修改 → DebounceEntry 转 Map → 调用 processDebounced → 发送 AI 回复 + 媒体附件 */
    private void flushBatch() {
        List<DebounceEntry> batch;
        synchronized (pending) {
            batch = List.copyOf(pending);  // 快照拷贝
            pending.clear();               // 清空排队队列
            debounceTask = null;
        }
        if (batch.isEmpty()) return;
        ILinkClient cl = client;
        if (cl == null) return;
        try {
            // 取最后一条消息的用户 ID（约定同一防抖批次为同一用户）
            String fromUserId = batch.get(batch.size() - 1).fromUserId;
            // DebounceEntry → Map 列表，传给 processDebounced
            List<Map<String, Object>> entries = batch.stream().map(DebounceEntry::toMap).toList();

            String summary = entries.stream()
                    .map(e -> e.getOrDefault("text", "[图片]").toString())
                    .collect(Collectors.joining(" | "));
            log.info("防抖合并 {} 条: {}", batch.size(), summary);

            // 核心调用：交给 AiSdkChatServiceImpl 带工具调用、多模态、上下文压缩的 AI 服务
            String reply = aiChatService.processDebounced(entries);
            if (reply != null) {
                cl.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            // 推送 AI 产出的媒体文件（生图/语音等工具生成的图片、音频）
            flushPendingImages(cl, fromUserId);
        } catch (Exception e) {
            log.error("防抖批次处理失败", e);
        }
    }

    /** 推送 AI 产出的媒体文件（生图/语音等工具生成的图片、音频） */
    private void flushPendingImages(ILinkClient cl, String toUserId) {
        // 从 AiChatService 取出积压待发媒体
        List<Map.Entry<byte[], String>> media = aiChatService.drainPendingImages();
        for (Map.Entry<byte[], String> entry : media) {
            byte[] data = entry.getKey();
            String fn = entry.getValue();
            // 区分音频/图片最大体积限制
            long maxSize = fn.endsWith(".wav") || fn.endsWith(".mp3") || fn.endsWith(".silk") ? 15_000_000 : 5_000_000;
            if (data == null || data.length == 0) { log.warn("积压媒体为空，跳过: {}", fn); continue; }
            if (data.length > maxSize) { log.warn("积压媒体过大 ({} KB)，跳过: {}", data.length / 1024, fn); continue; }
            // 发送失败重试 1 次
            for (int retry = 0; retry < 2; retry++) {
                try {
                    // 音频发文件，图片发图片接口
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
}
