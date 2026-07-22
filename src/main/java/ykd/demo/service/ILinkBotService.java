package ykd.demo.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ILinkBotService {

    private final ILinkClient client;
    private final AiChatService aiChatService;

    // 防抖：4秒内连续文字消息合并成一条
    private final List<DebounceEntry> pendingTexts = new ArrayList<>();
    private ScheduledFuture<?> debounceTask;
    private static final long DEBOUNCE_MS = 10_000;
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "debounce");
        t.setDaemon(true);
        return t;
    });

    private record DebounceEntry(String fromUserId, String text) {}

    public ILinkBotService(AiChatService aiChatService) {
        this.aiChatService = aiChatService;

        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(5000L)
                .build();

        this.client = ILinkClient.builder()
                .config(config)
                .onLogin(new com.github.wechat.ilink.sdk.core.listener.OnLoginListener() {
                    @Override
                    public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext ctx) {
                        log.info("登录成功！BotID={}", ctx.getBotId());
                    }
                    @Override
                    public void onLoginFailure(Throwable throwable) {
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
            String qrCodeUrl = client.executeLogin();
            log.info("请扫码登录：{}", qrCodeUrl);
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
            try {
                handleSingleMessage(msg);
            } catch (Exception e) {
                log.error("处理消息失败", e);
            }
        }
    }

    private void handleSingleMessage(WeixinMessage msg) throws IOException {
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        String text = null;
        MessageItem imageItem = null;
        MessageItem voiceItem = null;
        MessageItem fileItem = null;
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

        // 图片：立即处理，不走防抖
        if (imageItem != null) {
            client.sendText(fromUserId, "正在思考……");
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
            String reply = aiChatService.chatWithImageBase64(prompt, base64);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
            return;
        }

        // 语音：立即处理，不走防抖
        if (voiceItem != null) {
            client.sendText(fromUserId, "正在思考……");
            log.info("收到语音消息 [{}]", fromUserId);
            String voiceText = null;
            com.github.wechat.ilink.sdk.core.model.VoiceItem voiceDetail = voiceItem.getVoice_item();
            if (voiceDetail != null && voiceDetail.getText() != null && !voiceDetail.getText().isBlank())
                voiceText = voiceDetail.getText();
            if (voiceText == null) {
                byte[] voiceBytes = client.downloadVoiceFromMessageItem(voiceItem);
                voiceText = aiChatService.speechToText(voiceBytes);
            }
            if (voiceText == null || voiceText.isBlank()) {
                client.sendText(fromUserId, "抱歉，没听清，请再说一遍");
                return;
            }
            log.info("语音识别：{}", voiceText);
            String reply = aiChatService.chatWithTools(voiceText);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
            return;
        }

        // 文件：立即处理，不走防抖
        if (fileItem != null) {
            client.sendText(fromUserId, "正在思考……");
            log.info("收到文件消息 [{}]", fromUserId);
            String fileName = fileItem.getFile_item() != null ? fileItem.getFile_item().getFile_name() : "";
            if (!fileName.toLowerCase().matches(".*\\.(mp3|wav|ogg|m4a|aac|wma)$")) {
                client.sendText(fromUserId, "暂不支持此文件类型");
                return;
            }
            try {
                byte[] fileBytes = client.downloadFileFromMessageItem(fileItem);
                if (fileBytes == null || fileBytes.length == 0) {
                    client.sendText(fromUserId, "文件下载失败");
                    return;
                }
                String voiceText = aiChatService.speechToText(fileBytes);
                if (voiceText == null || voiceText.isBlank()) {
                    client.sendText(fromUserId, "无法识别音频");
                    return;
                }
                log.info("音频识别：{}", voiceText);
                String reply = aiChatService.chatWithTools(voiceText);
                if (reply != null) {
                    client.sendText(fromUserId, reply);
                    log.info("回复 [{}]: {}", fromUserId, reply);
                }
                flushPendingImages(fromUserId);
            } catch (Exception e) {
                log.error("处理文件失败", e);
                client.sendText(fromUserId, "处理文件失败");
            }
            return;
        }

        // 纯文字：走防抖
        if (text == null || text.isBlank()) return;
        log.info("收到文字消息 [{}]: {}", fromUserId, text);

        //立刻提示，让用户知道消息收到了
        client.sendText(fromUserId, "正在思考……");

        synchronized (pendingTexts) {
            pendingTexts.add(new DebounceEntry(fromUserId, text));
            if (debounceTask != null) debounceTask.cancel(false);
            debounceTask = debouncer.schedule(this::flushTextBatch, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 防抖超时后，合并所有排队文字消息一起发给 AI */
    private void flushTextBatch() {
        List<DebounceEntry> batch;
        synchronized (pendingTexts) {
            batch = List.copyOf(pendingTexts);
            pendingTexts.clear();
            debounceTask = null;
        }

        if (batch.isEmpty()) return;

        try {
            String fromUserId = batch.get(batch.size() - 1).fromUserId;
            String combined = batch.stream().map(DebounceEntry::text).collect(Collectors.joining("\n---\n"));
            log.info("防抖合并 {} 条消息，发给 AI: {}", batch.size(), combined);

            String reply = aiChatService.chatWithTools(combined);
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
        List<Map.Entry<byte[], String>> pending = aiChatService.drainPendingImages();
        for (Map.Entry<byte[], String> entry : pending) {
            try {
                String fileName = entry.getValue();
                if (fileName.endsWith(".wav") || fileName.endsWith(".mp3") || fileName.endsWith(".silk")) {
                    client.sendFile(toUserId, entry.getKey(), fileName, "点击播放");
                    log.info("积压语音文件已发送 [{}]: {}", toUserId, fileName);
                } else {
                    client.sendImage(toUserId, entry.getKey(), fileName, "");
                    log.info("积压图片已发送 [{}]: {}", toUserId, fileName);
                }
            } catch (Exception e) {
                log.error("发送积压文件失败 [{}]", toUserId, e);
            }
        }
    }
}