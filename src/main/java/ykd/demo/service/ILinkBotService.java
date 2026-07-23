package ykd.demo.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.tool.AttachmentStore;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ILinkBotService {

    private final ILinkClient client;
    private final AiChatService aiChatService;
    private final AttachmentStore attachmentStore;

    // 防抖：10 秒内所有类型消息合并成一条
    private final List<DebounceEntry> pending = new ArrayList<>();
    private ScheduledFuture<?> debounceTask;
    private static final long DEBOUNCE_MS = 10_000;
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
        String base64;     // IMAGE 专用

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

    public ILinkBotService(AiChatService aiChatService, AttachmentStore attachmentStore) {
        this.aiChatService = aiChatService;
        this.attachmentStore = attachmentStore;

        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(true).heartbeatIntervalMs(5000L).build();

        this.client = ILinkClient.builder()
                .config(config)
                .onLogin(new com.github.wechat.ilink.sdk.core.listener.OnLoginListener() {
                    @Override public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext ctx) {
                        log.info("登录成功！BotID={}", ctx.getBotId());
                    }
                    @Override public void onLoginFailure(Throwable throwable) {
                        log.error("iLink 登录失败", throwable);
                    }
                })
                .onDisconnect(new com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener() {
                    @Override public void onDisconnect(Throwable cause) { log.warn("iLink 连接断开"); }
                    @Override public void onReconnectStart(int attempt) {}
                    @Override public void onReconnectSuccess() {}
                    @Override public void onReconnectFailed(Throwable cause) {}
                })
                .onMessage(messages -> handleMessages(messages))
                .build();
    }

    public void start() {
        try {
            log.info("请扫码登录：{}", client.executeLogin());
        } catch (Exception e) {
            log.error("iLink 登录失败", e);
        }
    }

    public void stop() {
        debouncer.shutdown();
        client.close();
    }

    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            try { handleSingleMessage(msg); }
            catch (Exception e) { log.error("处理消息失败", e); }
        }
    }

    private void handleSingleMessage(WeixinMessage msg) throws IOException {
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        // 解析 items
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

        client.sendText(fromUserId, "正在思考……");

        // 图片 → 下载 + 存 ImageStore → 排队
        if (imageItem != null) {
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
            attachmentStore.set(base64, prompt);
            synchronized (pending) {
                pending.add(DebounceEntry.image(fromUserId, prompt, base64));
                resetDebounce();
            }
            return;
        }

        // 语音 → ASR（立即，轻量）→ 排队
        if (voiceItem != null) {
            log.info("收到语音消息 [{}]", fromUserId);
            String voiceText = extractVoiceText(voiceItem);
            if (voiceText == null || voiceText.isBlank()) {
                client.sendText(fromUserId, "抱歉，没听清，请再说一遍");
                return;
            }
            log.info("语音识别：{}", voiceText);
            synchronized (pending) {
                pending.add(DebounceEntry.text(fromUserId, voiceText));
                resetDebounce();
            }
            return;
        }

        // 文件（音频）→ ASR（立即，轻量）→ 排队
        if (fileItem != null) {
            log.info("收到文件消息 [{}]", fromUserId);
            String fn = fileItem.getFile_item() != null ? fileItem.getFile_item().getFile_name() : "";

            // 音频 → ASR → 排队
            if (fn.toLowerCase().matches(".*\\.(mp3|wav|ogg|m4a|aac|wma)$")) {
                try {
                    byte[] fileBytes = client.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        client.sendText(fromUserId, "文件下载失败"); return;
                    }
                    String voiceText = aiChatService.speechToText(fileBytes);
                    if (voiceText == null || voiceText.isBlank()) {
                        client.sendText(fromUserId, "无法识别音频"); return;
                    }
                    log.info("音频识别：{}", voiceText);
                    synchronized (pending) {
                        pending.add(DebounceEntry.text(fromUserId, voiceText));
                        resetDebounce();
                    }
                } catch (Exception e) {
                    log.error("处理音频文件失败", e);
                    client.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            // 文档（PDF/Word/Excel/文本）→ 下载 → 暂存 imageStore → 排队
            if (fn.toLowerCase().matches(".*\\.(pdf|docx|xlsx|txt|md|csv|html)$")) {
                try {
                    byte[] fileBytes = client.downloadFileFromMessageItem(fileItem);
                    if (fileBytes == null || fileBytes.length == 0) {
                        client.sendText(fromUserId, "文件下载失败"); return;
                    }
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    attachmentStore.set(base64, "读取文件内容");
                    synchronized (pending) {
                        pending.add(DebounceEntry.text(fromUserId,
                            "用户发送了文件：" + fn + "。请用 read_file 工具，path 参数填 **image_store** 即可读取此文件内容"));
                        resetDebounce();
                    }
                } catch (Exception e) {
                    log.error("处理文档文件失败", e);
                    client.sendText(fromUserId, "处理文件失败");
                }
                return;
            }

            client.sendText(fromUserId, "暂不支持此文件类型（支持：音频 mp3/wav + 文档 pdf/docx/xlsx/txt）");
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

    /** 提取语音文字：优先 SDK 自带识别，否则调 ASR */
    private String extractVoiceText(MessageItem voiceItem) throws IOException {
        com.github.wechat.ilink.sdk.core.model.VoiceItem vd = voiceItem.getVoice_item();
        if (vd != null && vd.getText() != null && !vd.getText().isBlank()) return vd.getText();
        byte[] vb = client.downloadVoiceFromMessageItem(voiceItem);
        return aiChatService.speechToText(vb);
    }

    private void resetDebounce() {
        if (debounceTask != null) debounceTask.cancel(false);
        debounceTask = debouncer.schedule(this::flushBatch, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** 防抖超时：所有排队消息合并，一次 AI 调用处理 */
    private void flushBatch() {
        List<DebounceEntry> batch;
        synchronized (pending) {
            batch = List.copyOf(pending);
            pending.clear();
            debounceTask = null;
        }
        if (batch.isEmpty()) return;
        try {
            String fromUserId = batch.get(batch.size() - 1).fromUserId;
            List<Map<String, Object>> entries = batch.stream().map(DebounceEntry::toMap).toList();
            String summary = entries.stream()
                    .map(e -> e.getOrDefault("text", "[图片]").toString())
                    .collect(Collectors.joining(" | "));
            log.info("防抖合并 {} 条: {}", batch.size(), summary);

            String reply = aiChatService.processDebounced(entries);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
        } catch (Exception e) {
            log.error("防抖批次处理失败", e);
        }
    }

    private void flushPendingImages(String toUserId) {
        List<Map.Entry<byte[], String>> media = aiChatService.drainPendingImages();
        for (Map.Entry<byte[], String> entry : media) {
            try {
                String fn = entry.getValue();
                if (fn.endsWith(".wav") || fn.endsWith(".mp3") || fn.endsWith(".silk"))
                    client.sendFile(toUserId, entry.getKey(), fn, "点击播放");
                else
                    client.sendImage(toUserId, entry.getKey(), fn, "");
                log.info("积压媒体已发送 [{}]: {}", toUserId, fn);
            } catch (Exception e) {
                log.error("发送积压媒体失败 [{}]", toUserId, e);
            }
        }
    }
}