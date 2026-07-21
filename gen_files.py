import os

base = "/d/ykd-spring/demo/src/main/java/ykd/demo/service"

impl_content = '''package ykd.demo.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.service.AiChatService;
import ykd.demo.service.WeatherService;
import ykd.demo.service.SearchService;

import java.util.AbstractMap;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiSdkChatServiceImpl implements AiChatService {

    private final MultiModalConversation conversation;
    private List<MultiModalMessage> messages;
    private final String apiKey;
    private final ImageSynthesis imageSynthesis;
    private final RestClient deepSeekClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WeatherService weatherService;
    private final SearchService searchService;

    private final List<Map.Entry<byte[], String>> pendingImages = new ArrayList<>();

    private AudioParameters.Voice currentVoice = AudioParameters.Voice.CHERRY;
    private String lastUsage = "";
    private static final int MAX_ROUNDS = 50;
    private static final int KEEP_ROUNDS = 10;

    public AiSdkChatServiceImpl(WeatherService weatherService, SearchService searchService) {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        this.conversation = new MultiModalConversation();
        this.messages = new ArrayList<>();
        this.imageSynthesis = new ImageSynthesis();
        this.deepSeekClient = RestClient.builder()
                .baseUrl("https://api.deepseek.com")
                .build();
        this.weatherService = weatherService;
        this.searchService = searchService;
    }

    @Override
    public List<Map.Entry<byte[], String>> drainPendingImages() {
        List<Map.Entry<byte[], String>> result = new ArrayList<>(pendingImages);
        pendingImages.clear();
        return result;
    }

    private List<ToolBase> buildTools() {
        JsonObject weatherParams = new JsonObject();
        weatherParams.addProperty("type", "object");
        JsonObject weatherProps = new JsonObject();
        JsonObject cityProp = new JsonObject();
        cityProp.addProperty("type", "string");
        cityProp.addProperty("description", "城市名，如北京、上海、广州");
        weatherProps.add("city", cityProp);
        weatherParams.add("properties", weatherProps);
        JsonArray weatherRequired = new JsonArray();
        weatherRequired.add("city");
        weatherParams.add("required", weatherRequired);

        FunctionDefinition weatherDef = FunctionDefinition.builder()
                .name("get_weather")
                .description("查询某个城市的实时天气")
                .parameters(weatherParams)
                .build();

        JsonObject imageParams = new JsonObject();
        imageParams.addProperty("type", "object");
        JsonObject imageProps = new JsonObject();
        JsonObject promptProp = new JsonObject();
        promptProp.addProperty("type", "string");
        promptProp.addProperty("description", "图片描述，建议用英文");
        imageProps.add("prompt", promptProp);
        imageParams.add("properties", imageProps);
        JsonArray imageRequired = new JsonArray();
        imageRequired.add("prompt");
        imageParams.add("required", imageRequired);

        FunctionDefinition imageDef = FunctionDefinition.builder()
                .name("generate_image")
                .description("根据文字描述生成一张图片")
                .parameters(imageParams)
                .build();

        JsonObject searchParams = new JsonObject();
        searchParams.addProperty("type", "object");
        JsonObject searchProps = new JsonObject();
        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");
        queryProp.addProperty("description", "搜索关键词");
        searchProps.add("query", queryProp);
        searchParams.add("properties", searchProps);
        JsonArray searchRequired = new JsonArray();
        searchRequired.add("query");
        searchParams.add("required", searchRequired);

        FunctionDefinition searchDef = FunctionDefinition.builder()
                .name("get_realtime_info")
                .description("搜索实时信息，如新闻、股价、热点")
                .parameters(searchParams)
                .build();

        return Arrays.asList(
                ToolFunction.builder().function(weatherDef).build(),
                ToolFunction.builder().function(imageDef).build(),
                ToolFunction.builder().function(searchDef).build()
        );
    }

    private String executeToolCall(ToolCallFunction.CallFunction func) {
        String name = func.getName();
        String argsJson = func.getArguments();
        try {
            JsonObject args = JsonParser.parseString(argsJson).getAsJsonObject();
            return switch (name) {
                case "get_weather" -> {
                    String city = args.get("city").getAsString();
                    log.info("Function Calling -> query weather, city={}", city);
                    yield weatherService.getWeather(city);
                }
                case "generate_image" -> {
                    String prompt = args.get("prompt").getAsString();
                    log.info("Function Calling -> generate image, prompt={}", prompt);
                    String imageUrl = generateImage(prompt);
                    if (imageUrl.startsWith("http")) {
                        try {
                            byte[] imgBytes;
                            try (java.io.InputStream in = new java.net.URL(imageUrl).openStream()) {
                                imgBytes = in.readAllBytes();
                            }
                            if (imgBytes != null && imgBytes.length > 0) {
                                pendingImages.add(new AbstractMap.SimpleEntry<>(imgBytes, "generated.png"));
                            }
                        } catch (Exception ex) {
                            log.warn("download generated image failed", ex);
                        }
                    }
                    yield "图片已生成，请在回复中告知用户图片已发送。";
                }
                case "get_realtime_info" -> {
                    String query = args.get("query").getAsString();
                    log.info("Function Calling -> web search, query={}", query);
                    yield searchService.search(query);
                }
                default -> {
                    log.warn("unknown tool call: {}", name);
                    yield "unknown tool";
                }
            };
        } catch (Exception e) {
            log.error("execute tool call failed", e);
            return "execute failed: " + e.getMessage();
        }
    }

    @Override
    public String chatWithTools(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "请输入想说的话";
        }

        String timedMessage = "[" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + "] " + userMessage;

        log.info("Function Calling chat, userMessage={}", userMessage);

        try {
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", timedMessage)))
                    .build();
            messages.add(userMsg);

            List<ToolBase> tools = buildTools();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages).tools(tools).build();

            MultiModalConversationResult result = conversation.call(param);
            MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
            List<ToolCallBase> toolCalls = responseMsg.getToolCalls();

            if (toolCalls == null || toolCalls.isEmpty()) {
                String reply = extractText(result);
                recordUsage(result);
                MultiModalMessage assistantMsg = MultiModalMessage.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(Collections.singletonList(Collections.singletonMap("text", reply)))
                        .build();
                messages.add(assistantMsg);
                trimContext();
                return reply;
            }

            messages.add(responseMsg);
            for (ToolCallBase toolCall : toolCalls) {
                if (toolCall instanceof ToolCallFunction) {
                    ToolCallFunction.CallFunction func = ((ToolCallFunction) toolCall).getFunction();
                    String toolResult = executeToolCall(func);
                    MultiModalMessage toolMsg = MultiModalMessage.builder()
                            .role("tool").toolCallId(toolCall.getId())
                            .content(Collections.singletonList(Collections.singletonMap("text", toolResult)))
                            .build();
                    messages.add(toolMsg);
                }
            }

            MultiModalConversationParam param2 = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages).build();

            MultiModalConversationResult result2 = conversation.call(param2);
            String finalReply = extractText(result2);
            recordUsage(result2);

            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", finalReply)))
                    .build();
            messages.add(assistantMsg);
            trimContext();
            return finalReply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("Function Calling chat failed", e);
            return "AI暂时无法回复，请稍后再试";
        }
    }

    @Override
    public String chat(String userMessage) {
        return chatWithTools(userMessage);
    }

    @Override
    public String chatWithSearch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "请输入搜索内容";
        log.info("search, userMessage={}", userMessage);
        try {
            MultiModalMessage systemMsg = MultiModalMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text",
                            "你是需要实时信息的助手，必须使用搜索获取最新数据，不得依赖知识库。")))
                    .build();
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", userMessage)))
                    .build();
            List<MultiModalMessage> searchMessages = new ArrayList<>();
            searchMessages.add(systemMsg);
            searchMessages.addAll(this.messages);
            searchMessages.add(userMsg);
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(searchMessages).enableSearch(true).build();
            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);
            recordUsage(result);
            messages.add(userMsg);
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply)))
                    .build();
            messages.add(assistantMsg);
            return reply;
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("search failed", e);
            return "搜索失败";
        }
    }

    @Override
    public String chatWithImage(String text, String imageUrl) {
        if (text == null || text.isBlank()) text = "描述这张图片";
        log.info("image request, text={}, imageUrl={}", text, imageUrl);
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", imageUrl));
            content.add(Collections.singletonMap("text", text));
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue()).content(content).build();
            messages.add(userMsg);
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages).build();
            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);
            recordUsage(result);
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply)))
                    .build();
            messages.add(assistantMsg);
            return reply;
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("image recognition failed", e);
            return "图片识别失败";
        }
    }

    @Override
    public String chatWithImageBase64(String text, String base64Image) {
        if (text == null || text.isBlank()) text = "描述这张图片";
        log.info("base64 image, text={}", text);
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64Image));
            content.add(Collections.singletonMap("text", text));
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue()).content(content).build();
            messages.add(userMsg);
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages).build();
            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);
            recordUsage(result);
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply)))
                    .build();
            messages.add(assistantMsg);
            return reply;
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("base64 image failed", e);
            return "图片识别失败";
        }
    }

    @Override
    public String generateImage(String prompt) {
        if (prompt == null || prompt.isBlank()) return "请描述要生成的图片";
        log.info("generate image, prompt={}", prompt);
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey).model("wanx2.1-t2i-turbo").prompt(prompt).n(1).size("1024*1024").build();
            ImageSynthesisResult result = imageSynthesis.call(param);
            ImageSynthesisOutput output = result.getOutput();
            if (output == null || output.getResults() == null || output.getResults().isEmpty())
                return "生成失败";
            String imageUrl = output.getResults().get(0).get("url");
            log.info("image generated, url={}", imageUrl);
            return imageUrl;
        } catch (ApiException | NoApiKeyException e) {
            log.error("generate image failed", e);
            return "生成失败";
        }
    }

    @Override
    public byte[] textToSpeech(String text) {
        if (text == null || text.isBlank()) return new byte[0];
        log.info("TTS, text={}, voice={}", text, currentVoice);
        try {
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3-tts-flash").text(text).voice(currentVoice).build();
            MultiModalConversationResult result = conversation.call(param);
            String audioUrl = result.getOutput().getAudio().getUrl();
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("TTS empty URL");
                return new byte[0];
            }
            log.info("TTS success, audioUrl={}", audioUrl);
            try (java.io.InputStream in = new java.net.URL(audioUrl).openStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.error("TTS failed", e);
            return new byte[0];
        }
    }

    @Override
    public String speechToText(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return "";
        log.info("ASR, size={} bytes", audioBytes.length);
        try {
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
            String requestBody = String.format("""
                    {
                        "model": "qwen3-asr-flash",
                        "messages": [{
                            "role": "user",
                            "content": [{
                                "type": "audio",
                                "audio": "data:audio/wav;base64,%s"
                            }]
                        }]
                    }
                    """, base64Audio);
            RestClient asrClient = RestClient.builder().baseUrl("https://dashscope.aliyuncs.com").build();
            String responseJson = asrClient.post()
                    .uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody).retrieve().body(String.class);
            return mapper.readTree(responseJson).path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("ASR failed", e);
            return "";
        }
    }

    @Override
    public String setVoice(String name) {
        if (name == null || name.isBlank()) return "音色名不能为空";
        try {
            currentVoice = AudioParameters.Voice.valueOf(name.toUpperCase());
            log.info("voice changed to: {}", name);
            return "音色已切换为：" + name;
        } catch (IllegalArgumentException e) {
            return "无效音色：" + name + "，可用：Cherry, Serena, Ethan...";
        }
    }

    @Override public String getCurrentVoice() { return currentVoice.name(); }
    @Override public String getLastUsage() { return lastUsage; }

    @Override
    public String getRecentHistory(int count) {
        if (messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - count);
        for (int i = start; i < messages.size(); i++) {
            MultiModalMessage msg = messages.get(i);
            String role = msg.getRole();
            String text = "";
            List<Map<String, Object>> contentList = msg.getContent();
            if (contentList != null && !contentList.isEmpty()) {
                Object textObj = contentList.get(0).get("text");
                if (textObj != null) text = textObj.toString();
            }
            if (!text.isBlank()) sb.append(role).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private void recordUsage(MultiModalConversationResult result) {
        try {
            String usageJson = mapper.writeValueAsString(result.getUsage());
            log.debug("raw usage: {}", usageJson);
            JsonNode usage = mapper.readTree(usageJson);
            int inputTokens = usage.path("input_tokens").asInt(0);
            if (inputTokens == 0) inputTokens = usage.path("inputTokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            if (outputTokens == 0) outputTokens = usage.path("outputTokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(0);
            if (totalTokens == 0) totalTokens = usage.path("totalTokens").asInt(0);
            lastUsage = String.format("Token | input %d | output %d | total %d", inputTokens, outputTokens, totalTokens);
            log.info("Token: {}", lastUsage);
        } catch (Exception e) {
            log.warn("extract usage failed", e);
            lastUsage = "";
        }
    }

    private String extractText(MultiModalConversationResult result) {
        List<Map<String, Object>> contentList = result.getOutput().getChoices().get(0).getMessage().getContent();
        if (contentList == null) return "";
        for (Map<String, Object> item : contentList) {
            if (item.containsKey("text")) {
                Object textObj = item.get("text");
                return textObj != null ? textObj.toString() : "";
            }
        }
        return "";
    }

    private void trimContext() {
        int roundCount = (messages.size() - 1) / 2;
        if (roundCount <= MAX_ROUNDS) return;
        log.info("context {} rounds, trim...", roundCount);
        int keepStart = messages.size() - KEEP_ROUNDS * 2;
        List<MultiModalMessage> toSummarize = messages.subList(1, keepStart);
        List<MultiModalMessage> toKeep = messages.subList(keepStart, messages.size());
        String summary = summarize(toSummarize);
        if (summary == null || summary.isBlank()) { log.warn("summary failed"); return; }
        List<MultiModalMessage> newMessages = new ArrayList<>();
        newMessages.add(messages.get(0));
        MultiModalMessage summaryMsg = MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(Collections.singletonList(Collections.singletonMap("text", "【历史摘要】" + summary)))
                .build();
        newMessages.add(summaryMsg);
        newMessages.addAll(toKeep);
        this.messages = newMessages;
        log.info("trim done, {} -> {}", messages.size(), newMessages.size());
    }

    private String summarize(List<MultiModalMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("总结对话：\n");
        for (MultiModalMessage msg : history) {
            List<Map<String, Object>> contentList = msg.getContent();
            if (contentList == null || contentList.isEmpty()) continue;
            Object textObj = contentList.get(0).get("text");
            if (textObj == null) continue;
            String text = textObj.toString();
            if (text.isBlank()) continue;
            sb.append("user".equals(msg.getRole()) ? "用户: " : "AI: ").append(text).append("\n");
        }
        String requestBody = String.format(
                "{\"model\": \"deepseek-v4-flash\", \"messages\": [{\"role\": \"user\", \"content\": \"%s\"}]}",
                sb.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));
        try {
            String responseJson = deepSeekClient.post()
                    .uri("/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                    .body(requestBody).retrieve().body(String.class);
            return mapper.readTree(responseJson).path("choices").get(0).path("message").path("content").asText().trim();
        } catch (Exception e) {
            log.error("summarize failed", e);
            return null;
        }
    }
}
'''

ilink_content = '''package ykd.demo.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ILinkBotService {

    private final ILinkClient client;
    private final AiChatService aiChatService;
    private final SearchService searchService;
    private final WeatherService weatherService;

    public ILinkBotService(AiChatService aiChatService,
                           SearchService searchService,
                           WeatherService weatherService) {
        this.aiChatService = aiChatService;
        this.searchService = searchService;
        this.weatherService = weatherService;

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

        client.sendText(fromUserId, "正在思考……");

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

        String reply;
        String voiceText = null;

        if (imageItem != null) {
            log.info("收到图片消息 [{}]", fromUserId);
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = (text == null || text.isBlank()) ? "描述这张图片" : text;
            reply = aiChatService.chatWithImageBase64(prompt, base64);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
        } else if (voiceItem != null) {
            log.info("收到语音消息 [{}]", fromUserId);
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
            reply = handleText(fromUserId, voiceText);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
        } else if (fileItem != null) {
            log.info("收到文件消息 [{}]", fromUserId);
            String fileName = fileItem.getFile_item() != null ? fileItem.getFile_item().getFile_name() : "";
            if (!fileName.toLowerCase().matches(".*\\\\.(mp3|wav|ogg|m4a|aac|wma)$")) {
                client.sendText(fromUserId, "暂不支持此文件类型");
                return;
            }
            try {
                byte[] fileBytes = client.downloadFileFromMessageItem(fileItem);
                if (fileBytes == null || fileBytes.length == 0) {
                    client.sendText(fromUserId, "文件下载失败");
                    return;
                }
                voiceText = aiChatService.speechToText(fileBytes);
                if (voiceText == null || voiceText.isBlank()) {
                    client.sendText(fromUserId, "无法识别音频");
                    return;
                }
                log.info("音频识别：{}", voiceText);
                reply = handleText(fromUserId, voiceText);
                if (reply != null) {
                    client.sendText(fromUserId, reply);
                    log.info("回复 [{}]: {}", fromUserId, reply);
                }
                flushPendingImages(fromUserId);
            } catch (Exception e) {
                log.error("处理文件失败", e);
                client.sendText(fromUserId, "处理文件失败");
            }
        } else {
            if (text == null || text.isBlank()) return;
            log.info("收到文字消息 [{}]: {}", fromUserId, text);
            reply = handleText(fromUserId, text);
            if (reply != null) {
                client.sendText(fromUserId, reply);
                log.info("回复 [{}]: {}", fromUserId, reply);
            }
            flushPendingImages(fromUserId);
        }
    }

    private String handleText(String fromUserId, String raw) {
        if (raw.equalsIgnoreCase("/voices"))
            return "可用音色：Cherry, Serena, Ethan, Chelsie...\\n使用 /voice 音色名 切换";
        if (raw.toLowerCase().startsWith("/voice ")) {
            return aiChatService.setVoice(raw.substring(7).trim());
        }
        return aiChatService.chatWithTools(raw);
    }

    private void flushPendingImages(String toUserId) {
        List<Map.Entry<byte[], String>> pending = aiChatService.drainPendingImages();
        for (Map.Entry<byte[], String> entry : pending) {
            try {
                client.sendImage(toUserId, entry.getKey(), entry.getValue(), "");
                log.info("积压图片已发送 [{}]", toUserId);
            } catch (Exception e) {
                log.error("发送积压图片失败", e);
            }
        }
    }
}
'''

with open(os.path.join(base, "impl", "AiSdkChatServiceImpl.java"), "w", encoding="utf-8") as f:
    f.write(impl_content)
print("impl done")

with open(os.path.join(base, "ILinkBotService.java"), "w", encoding="utf-8") as f:
    f.write(ilink_content)
print("ilink done")