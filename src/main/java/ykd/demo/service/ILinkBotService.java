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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

        // 先回一句"正在思考……"让用户知道收到了
        client.sendText(fromUserId, "正在思考……");

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

            // TTS → WAV 文件发回（iLink Bot 通道不支持语音气泡，降级为文件）
            byte[] wavBytes = aiSdkChatService.textToSpeech(aiReply);
            if (wavBytes.length > 0) {
                log.info("发送语音文件，大小={} 字节", wavBytes.length);
                try {
                    client.sendFile(fromUserId, wavBytes, "语音回复.wav", "点击播放");
                    log.info("语音文件发送完成");
                } catch (Exception e) {
                    log.error("语音文件发送失败", e);
                }
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
        String history = aiSdkChatService.getRecentHistory(6);
        Intent intent = intentRecognizer.recognize(raw, history);
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

    /** 生图意图：AI 润色提示词 → 调模型 → 发微信 */
    private String handleGenerateImage(String fromUserId, String raw) {
        String polishedPrompt = polishPromptWithDeepSeek(raw);
        log.info("润色后提示词：{}", polishedPrompt);

        String imageUrl = aiSdkChatService.generateImage(polishedPrompt);
        return sendGeneratedImageBack(fromUserId, imageUrl);
    }

    /** DeepSeek 润色生图提示词 */
    private String polishPromptWithDeepSeek(String raw) {
        try {
            String requestBody = """
            {
                "model": "deepseek-v4-flash",
                "messages": [
                    {"role": "system", "content": "你是一个 prompt 优化助手。用户想生成一张图片，请提取核心描述，去除命令词（画、生成、帮我等），适当润色增加细节和画面感，直接返回润色后的英文提示词，不要加解释。"},
                    {"role": "user", "content": "%s"}
                ]
            }
            """.formatted(raw.replace("\"", "\\\""));

            String responseJson = restClient.post()
                    .uri("https://api.deepseek.com/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);
            return root.path("choices").get(0).path("message").path("content").asText().trim();

        } catch (Exception e) {
            log.warn("DeepSeek 润色失败，使用原始描述", e);
            return raw;
        }
    }

    /** 天气意图：DeepSeek 提取城市名 → 调 WeatherService → AI 整理回复 */
    private String handleWeather(String raw) {
        String argument = extractCityWithDeepSeek(raw);

        if (argument.isBlank()) {
            return "请带城市名，如：/weather 北京";
        }

        log.info("提取城市名：{}", argument);

        String weatherData = weatherService.getWeather(argument);

        if (weatherData.startsWith("错误")) {
            return weatherData;
        }

        // 把天气数据和用户原问题一起发给 AI，让 AI 结合问题回答
        return aiSdkChatService.chat("天气数据：" + weatherData + "\n用户问题：" + raw + "\n请根据天气数据回答用户的问题");
    }

    /** DeepSeek 提取城市名 */
    private String extractCityWithDeepSeek(String raw) {
        String input = raw.replaceFirst("/weather", "").trim();
        if (input.isBlank()) {
            return "";
        }

        try {
            String requestBody = """
            {
                "model": "deepseek-v4-flash",
                "messages": [
                    {"role": "system", "content": "从用户的话中提取中国城市名，只返回城市名本身，不要任何解释。如果找不到城市名，返回空。"},
                    {"role": "user", "content": "%s"}
                ]
            }
            """.formatted(input.replace("\"", "\\\""));

            String responseJson = restClient.post()
                    .uri("https://api.deepseek.com/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);
            String city = root.path("choices").get(0).path("message").path("content").asText().trim();

            if (city.isBlank() || city.equals("空")) {
                return "";
            }
            return city;

        } catch (Exception e) {
            log.warn("DeepSeek 提取城市名失败", e);
            return input;
        }
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

    /** TTS：AI 生成回复 → TTS → WAV 文件发回（iLink Bot 通道不支持语音气泡） */
    private String handleTts(String fromUserId, String raw) {
        String aiReply = aiSdkChatService.chat(raw);

        byte[] wavBytes = aiSdkChatService.textToSpeech(aiReply);
        if (wavBytes.length == 0) {
            return aiReply;
        }

        try {
            log.info("发送语音文件，大小={} 字节", wavBytes.length);
            client.sendFile(fromUserId, wavBytes, "语音回复.wav", "点击播放");
        } catch (Exception e) {
            log.error("发送语音文件失败", e);
            return "语音发送失败：" + aiReply;
        }

        return null;
    }

    /** 搜索意图：提取搜索关键词 → 调 Tavily → AI 整理回复 */
    private String handleSearch(String raw) {
        // 先用 DeepSeek 从原文+上下文提取搜索关键词
        String searchQuery = extractSearchQuery(raw);
        log.info("提取的搜索关键词：{}", searchQuery);

        if (searchQuery.isBlank()) {
            return "请告诉我你想搜索什么";
        }

        String searchResult = searchService.search(searchQuery);
        return aiSdkChatService.chat("搜索结果如下：\n" + searchResult + "\n请根据以上搜索结果回答用户的问题：" + raw);
    }

    /** 用 DeepSeek 提取搜索关键词 */
    private String extractSearchQuery(String raw) {
        try {
            // 带上对话历史，让 DeepSeek 提取真正想搜的内容
            String history = aiSdkChatService.getRecentHistory(6);
            String context = (history == null || history.isBlank()) ? "" : "对话历史：\n" + history + "\n\n";

            String requestBody = """
            {
                "model": "deepseek-v4-flash",
                "messages": [
                    {"role": "system", "content": "你是一个搜索关键词提取助手。用户想搜索某个信息，请从对话历史和用户最新消息中提取最精准的搜索关键词。只返回关键词本身，不要加任何解释、引号、标点。如果用户提到了某个具体名称（游戏、人物、事件等），优先保留。示例：用户说'早发布了，你搜搜'，历史提到'王者荣耀世界'，返回：王者荣耀世界 发布 2026"},
                    {"role": "user", "content": "%s"}
                ]
            }
            """.formatted((context + "用户最新消息：" + raw).replace("\"", "\\\"").replace("\n", "\\n"));

            String responseJson = restClient.post()
                    .uri("https://api.deepseek.com/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);
            String keywords = root.path("choices").get(0).path("message").path("content").asText().trim();

            // 如果返回的是无效内容，用原文兜底
            if (keywords.isBlank() || keywords.length() > 100) {
                return raw;
            }
            return keywords;

        } catch (Exception e) {
            log.warn("提取搜索关键词失败，使用原文", e);
            return raw;
        }
    }
}