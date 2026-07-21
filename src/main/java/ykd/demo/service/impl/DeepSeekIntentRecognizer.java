package ykd.demo.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import ykd.demo.intent.Intent;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.service.IntentRecognizer;

@Slf4j
@Service
public class DeepSeekIntentRecognizer implements IntentRecognizer {

    private final String apiKey;
    private final RestClient restClient;
    private static final String BASE_URL = "https://api.deepseek.com";

    public DeepSeekIntentRecognizer() {
        this.apiKey = System.getenv("DEEPSEEK_API_KEY");
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    @Override
    public Intent recognize(String input, String historyContext) {
        if (input == null || input.isBlank()) {
            return Intent.UNKNOWN;
        }

        String historyPart = (historyContext == null || historyContext.isBlank())
                ? ""
                : "以下是最近的对话历史：\n" + historyContext + "\n\n";
        String systemContent = historyPart
                + "结合上面的对话历史（如果有），判断用户最新一条消息的意图。"
                + "只返回一个词：CHAT（聊天）、GENERATE_IMAGE（生成图片）、WEATHER（查天气）、"
                + "TTS（语音）、INT_SEARCH（需要联网搜索当前信息，如查询最新新闻、实时股价、"
                + "今日热点等需要实时数据的问题）、UNKNOWN（无法判断）。不要返回其他内容。";

        String requestBody = String.format("""
                {
                    "model": "deepseek-v4-flash",
                    "messages": [
                        {"role": "system", "content": "%s"},
                        {"role": "user", "content": "%s"}
                    ]
                }
                """,
                systemContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"),
                input.replace("\\", "\\\\").replace("\"", "\\\""));

        String responseJson = restClient.post()
                .uri("/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.info("DeepSeek 原始响应: {}", responseJson);

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);

            String intentStr = root.path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText()
                    .trim();

            log.info("DeepSeek 意图识别结果: {}", intentStr);

            return switch (intentStr) {
                case "CHAT"            -> Intent.CHAT;
                case "GENERATE_IMAGE"  -> Intent.GENERATE_IMAGE;
                case "WEATHER"         -> Intent.WEATHER;
                case "TTS"             -> Intent.TTS;
                case "INT_SEARCH"      -> Intent.INT_SEARCH;
                default                -> Intent.UNKNOWN;
            };

        } catch (Exception e) {
            log.error("解析 DeepSeek 响应失败", e);
            return Intent.UNKNOWN;
        }
    }

}