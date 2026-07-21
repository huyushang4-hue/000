package ykd.demo.service.impl;

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
                .name("get_weather").description("查询某个城市的实时天气").parameters(weatherParams).build();

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
                .name("generate_image").description("根据文字描述生成一张图片").parameters(imageParams).build();

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
                .name("get_realtime_info").description("搜索实时信息，如新闻、股价、热点").parameters(searchParams).build();

        JsonObject ttsParams = new JsonObject();
        ttsParams.addProperty("type", "object");
        JsonObject ttsProps = new JsonObject();
        JsonObject ttsTextProp = new JsonObject();
        ttsTextProp.addProperty("type", "string");
        ttsTextProp.addProperty("description", "要转为语音的文字内容");
        ttsProps.add("text", ttsTextProp);
        ttsParams.add("properties", ttsProps);
        JsonArray ttsRequired = new JsonArray();
        ttsRequired.add("text");
        ttsParams.add("required", ttsRequired);
        FunctionDefinition ttsDef = FunctionDefinition.builder()
                .name("text_to_speech").description("将文字转为语音文件（WAV格式）发送给用户").parameters(ttsParams).build();

        return Arrays.asList(
                ToolFunction.builder().function(weatherDef).build(),
                ToolFunction.builder().function(imageDef).build(),
                ToolFunction.builder().function(searchDef).build(),
                ToolFunction.builder().function(ttsDef).build()
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
                        try (java.io.InputStream in = new java.net.URL(imageUrl).openStream()) {
                            byte[] imgBytes = in.readAllBytes();
                            if (imgBytes.length > 0) {
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
                case "text_to_speech" -> {
                    String ttsText = args.get("text").getAsString();
                    log.info("Function Calling -> TTS, text={}", ttsText);
                    byte[] audioBytes = textToSpeech(ttsText);
                    if (audioBytes.length > 0) {
                        pendingImages.add(new AbstractMap.SimpleEntry<>(audioBytes, "语音回复.wav"));
                        yield "语音已生成并发送给用户";
                    } else {
                        yield "语音合成失败，请稍后再试";
                    }
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
        if (userMessage == null || userMessage.isBlank()) return "请输入想说的话";

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

            int maxRounds = 5;
            for (int round = 0; round < maxRounds; round++) {
                MultiModalConversationResult result = conversation.call(param);
                MultiModalMessage responseMsg = result.getOutput().getChoices().get(0).getMessage();
                List<ToolCallBase> toolCalls = responseMsg.getToolCalls();

                if (toolCalls == null || toolCalls.isEmpty()) {
                    String reply = extractText(result);
                    // 将最新回复加到消息历史
                    MultiModalMessage assistantMsg = MultiModalMessage.builder()
                            .role(Role.ASSISTANT.getValue())
                            .content(Collections.singletonList(Collections.singletonMap("text", reply)))
                            .build();
                    messages.add(assistantMsg);
                    recordUsage(result);
                    trimContext();
                    return reply;
                }

                // 还有工具调用：把 AI 的响应加入历史
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
                // 继续下一轮
                param = MultiModalConversationParam.builder()
                        .apiKey(apiKey).model("qwen3.7-plus").messages(messages).tools(tools).build();
            }

            log.warn("Function Calling exceeded max rounds, force returning last text");
            MultiModalConversationResult lastResult = conversation.call(param);
            String lastReply = extractText(lastResult);
            if (lastReply.isBlank()) lastReply = "AI暂时无法回复，请稍后再试";
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", lastReply)))
                    .build();
            messages.add(assistantMsg);
            recordUsage(lastResult);
            trimContext();
            return lastReply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("Function Calling chat failed", e);
            return "AI暂时无法回复，请稍后再试";
        }
    }

    @Override public String chat(String userMessage) { return chatWithTools(userMessage); }

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
                    .content(Collections.singletonList(Collections.singletonMap("text", userMessage))).build();
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
                    .content(Collections.singletonList(Collections.singletonMap("text", reply))).build();
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
        log.info("image, text={}, imageUrl={}", text, imageUrl);
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
                    .content(Collections.singletonList(Collections.singletonMap("text", reply))).build();
            messages.add(assistantMsg);
            return reply;
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("image failed", e);
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
                    .apiKey(apiKey).model("qwen3.7-plus").messages(messages)
                    .tools(buildTools()).enableSearch(true).build();
            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);
            recordUsage(result);
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("text", reply))).build();
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
            if (output == null || output.getResults() == null || output.getResults().isEmpty()) return "生成失败";
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
        try {
            Object content = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (content == null) return "";
            // 如果 content 是 List<Map> 格式
            if (content instanceof List) {
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                for (Map<String, Object> item : contentList) {
                    if (item.containsKey("text")) {
                        Object textObj = item.get("text");
                        return textObj != null ? textObj.toString() : "";
                    }
                }
                return "";
            }
            // 如果 content 直接是字符串
            return content.toString();
        } catch (Exception e) {
            log.warn("extractText failed", e);
            return "";
        }
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