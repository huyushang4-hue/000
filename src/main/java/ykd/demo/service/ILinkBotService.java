package ykd.demo.service;


import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import ykd.demo.intent.Intent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class ILinkBotService {

    private final ILinkClient client;
    private final WeatherService weatherService;
    private final AiSdkChatService aiSdkChatService;
    private final IntentRecognizer intentRecognizer;
    private final RestClient restClient = RestClient.create();

    public ILinkBotService(WeatherService weatherService,
                           AiSdkChatService aiSdkChatService,
                           IntentRecognizer intentRecognizer) {
        this.weatherService = weatherService;
        this.aiSdkChatService = aiSdkChatService;
        this.intentRecognizer = intentRecognizer;

        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(5000L)  // 每 5 秒拉一次消息，减少延迟
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

    /** 启动：扫码登录 */
    public void start() {
        try {
            String qrCodeUrl = client.executeLogin();
            log.info("请扫码登录：{}", qrCodeUrl);
        } catch (Exception e) {
            log.error("iLink 登录失败", e);
        }
    }

    /** 停止 */
    public void stop() {
        client.close();
    }

    /** 批量处理收到的消息 */
    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            try {
                handleSingleMessage(msg);
            } catch (Exception e) {
                log.error("处理消息失败", e);
            }
        }
    }

    /** 处理单条消息 */
    private void handleSingleMessage(WeixinMessage msg) throws IOException {
        String fromUserId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) {
            return;
        }

        // ① 先找第一条文字和图片
        String text = null;
        MessageItem imageItem = null;
        for (MessageItem item : items) {
            if (item.getType() == 1 && item.getText_item() != null && text == null) {
                text = item.getText_item().getText();
            }
            if (item.getType() == 2 && item.getImage_item() != null && imageItem == null) {
                imageItem = item;
            }
        }

        String reply;
        if (imageItem != null) {
            // ② 有图片 → 下载 → 转 base64 → 识图
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述一下这张图片" : text;
            reply = aiSdkChatService.chatWithImageBase64(prompt, base64);
        } else {
            // ③ 纯文字
            if (text == null || text.isBlank()) return;
            log.info("收到文字消息 [{}]: {}", fromUserId, text);
            reply = handleText(fromUserId, text);
        }

        // ④ 回复文字
        client.sendText(fromUserId, reply);
        log.info("回复 [{}]: {}", fromUserId, reply);
    }

    /** 处理纯文字 → 意图识别 → 分发 */
    private String handleText(String fromUserId, String raw) {
        Intent intent = intentRecognizer.recognize(raw);
        log.info("意图识别结果={}, input={}", intent, raw);

        return switch (intent) {
            case GENERATE_IMAGE -> handleGenerateImage(fromUserId, raw);
            case WEATHER        -> handleWeather(raw);
            case CHAT           -> aiSdkChatService.chat(raw);
            case UNKNOWN        -> "没听懂，可以再说一遍吗？";
        };
    }

    /** 生图意图：清洗关键词 → 调模型 → 发微信 */
    private String handleGenerateImage(String fromUserId, String raw) {
        String prompt = raw
                .replace("生成图片", "")
                .replace("画", "")
                .replace("一张", "")
                .replace("一个", "")
                .replace("一只", "")
                .replace("给我", "")
                .replace("帮我", "")
                .trim();
        String imageUrl = aiSdkChatService.generateImage(prompt);
        return sendGeneratedImageBack(fromUserId, imageUrl);
    }

    /** 天气意图：去掉 /weather 前缀 → 调 WeatherService */
    private String handleWeather(String raw) {
        String argument = raw.replaceFirst("/weather", "").trim();
        if (argument.isBlank()) {
            return "请带城市名，如：/weather 北京";
        }
        return weatherService.getWeather(argument);
    }

    /** 下载生成的图片并通过微信发回 */
    private String sendGeneratedImageBack(String toUserId, String imageUrl) {
        try {
            byte[] imageBytes = restClient.get()
                    .uri(URI.create(imageUrl))
                    .retrieve()
                    .body(byte[].class);

            if (imageBytes == null || imageBytes.length == 0) {
                return "图片下载失败，URL：" + imageUrl;
            }

            client.sendImage(toUserId, imageBytes, "generated.png", "");
            return "图片已生成并发送，请查收。";
        } catch (Exception e) {
            log.error("发送生成图片失败，url={}", imageUrl, e);
            return "图片生成成功但发送失败，URL：" + imageUrl;
        }
    }
}
