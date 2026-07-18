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
    private final DeepSeekIntentRecognizer intentRecognizer;
    private final RestClient restClient = RestClient.create();
    private final SearchService searchService;

    public ILinkBotService(WeatherService weatherService,
                           AiSdkChatService aiSdkChatService,
                           DeepSeekIntentRecognizer intentRecognizer,
                           SearchService searchService) {
        this.weatherService = weatherService;
        this.aiSdkChatService = aiSdkChatService;
        this.intentRecognizer = intentRecognizer;
        this.searchService = searchService;

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

        // ① 先找第一条文字、图片和语音
        String text = null;
        MessageItem imageItem = null;
        MessageItem voiceItem = null;
        for (MessageItem item : items) {
            if (item.getType() == 1 && item.getText_item() != null && text == null) {
                text = item.getText_item().getText();
            }
            if (item.getType() == 2 && item.getImage_item() != null && imageItem == null) {
                imageItem = item;
            }
            if (item.getType() == 3 && voiceItem == null) {
                voiceItem = item;
            }
        }

        String reply;
        String voiceText = null;
        if (imageItem != null) {
            // ② 有图片 → 下载 → 转 base64 → 识图
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述一下这张图片" : text;
            reply = aiSdkChatService.chatWithImageBase64(prompt, base64);
        } else if (voiceItem != null) {
            // ③ 语音消息 → ASR → AI 聊天 → TTS → 回复
            log.info("收到语音消息 [{}]", fromUserId);
            com.github.wechat.ilink.sdk.core.model.VoiceItem voiceDetail = voiceItem.getVoice_item();

            // 方案 A：如果 SDK 自带语音识别结果，直接用
            if (voiceDetail != null && voiceDetail.getText() != null && !voiceDetail.getText().isBlank()) {
                voiceText = voiceDetail.getText();
                log.info("语音消息自带识别文本：{}", voiceText);
            }

            if (voiceText == null) {
                // 方案 B：自己调 ASR
                byte[] voiceBytes = client.downloadVoiceFromMessageItem(voiceItem);
                voiceText = aiSdkChatService.speechToText(voiceBytes);
            }

            if (voiceText == null || voiceText.isBlank()) {
                client.sendText(fromUserId, "抱歉，没听清你说什么，可以再说一遍吗？");
                return;
            }

            log.info("语音识别为：{}", voiceText);

            // AI 聊天
            String aiReply = aiSdkChatService.chat(voiceText);

            // TTS 转语音发回
            byte[] replyAudio = aiSdkChatService.textToSpeech(aiReply);
            if (replyAudio.length > 0) {
                client.sendFile(fromUserId, replyAudio, "语音回复.wav", "");
            } else {
                client.sendText(fromUserId, aiReply);
            }
            return;
        } else {
            // ④ 纯文字
            if (text == null || text.isBlank()) return;
            log.info("收到文字消息 [{}]: {}", fromUserId, text);
            reply = handleText(fromUserId, text);
        }

        // ④ 回复文字（仅当 reply 不为 null 时才发）
        // TTS 意图会返回 null，因为已单独发了语音文件，不再重复发文字
        if (reply != null) {
            client.sendText(fromUserId, reply);
            log.info("回复 [{}]: {}", fromUserId, reply);
        }
    }

    /** 处理纯文字 → 意图识别 → 分发 */
    private String handleText(String fromUserId, String raw) {
        Intent intent = intentRecognizer.recognize(raw);
        log.info("意图识别结果={}, input={}", intent, raw);

        return switch (intent) {
            case GENERATE_IMAGE -> handleGenerateImage(fromUserId, raw);
            case WEATHER        -> handleWeather(raw);
            case CHAT           -> aiSdkChatService.chat(raw);
            case TTS            -> handleTts(fromUserId, raw);
            case INT_SEARCH     -> handleSearch(raw);
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

    /** 天气意图：提取城市名 → 调 WeatherService → AI 整理回复 */
    private String handleWeather(String raw) {
        // 去掉 /weather 前缀（如果有的话）
        String argument = raw.replaceFirst("/weather", "").trim();

        // 去掉自然语言中的干扰词
        argument = argument
                .replace("今天", "")
                .replace("明天", "")
                .replace("后天", "")
                .replace("天气", "")
                .replace("怎么样", "")
                .replace("什么", "")
                .replace("的", "")
                .replace("呢", "")
                .replace("啊", "")
                .replace("呀", "")
                .replace("?", "")
                .replace("？", "")
                .replace("联网搜一下", "")
                .replace("联网搜索", "")
                .replace("搜索", "")
                .trim();

        if (argument.isBlank()) {
            return "请带城市名，如：/weather 北京";
        }

        // 调高德 API 拿天气数据
        String weatherData = weatherService.getWeather(argument);

        // 查天气失败则直接返回错误
        if (weatherData.startsWith("错误")) {
            return weatherData;
        }

        // 让 AI 整理成自然语言，同时存入对话历史
        // 这样后面问"穿什么"就能关联
        return aiSdkChatService.chat("查询天气结果：" + weatherData + "，请用自然语言告诉我");
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

    /** TTS：AI 生成回复 → 转语音 → 发语音文件，不返回文字 */
    private String handleTts(String fromUserId, String raw) {
        // ① AI 生成回复
        String aiReply = aiSdkChatService.chat(raw);

        // ② 转语音
        byte[] audioBytes = aiSdkChatService.textToSpeech(aiReply);
        if (audioBytes.length == 0) {
            return "语音合成失败，请稍后重试";
        }

        // ③ 发语音文件
        try {
            log.info("发送语音文件，大小={} 字节", audioBytes.length);
            client.sendFile(fromUserId, audioBytes, "语音回复.wav", "点击播放语音回复");
        } catch (Exception e) {
            log.error("发送语音文件失败", e);
            return "语音发送失败：" + aiReply;
        }

        // ④ 不返回文字（返回 null 让调用者跳过发文字）
        return null;
    }

    /** 搜索意图：调 Tavily → AI 整理回复 */
    private String handleSearch(String raw) {
        // ① Tavily 搜索
        String searchResult = searchService.search(raw);

        // ② 让 AI 基于搜索结果回答
        return aiSdkChatService.chat("搜索结果如下：\n" + searchResult + "\n请根据以上搜索结果回答用户的问题：" + raw);
    }
}