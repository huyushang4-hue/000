package ykd.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import ykd.demo.intent.Intent;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class DeepSeekIntentRecognizer {
    private final String apiKey;
    private final RestClient restClient;
    private static final String BASE_URL = "https://api.deepseek.com";
    public DeepSeekIntentRecognizer() {
        this.apiKey = System.getenv("DEEPSEEK_API_KEY");
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }
    public Intent recognize(String input, String historyContext) {
        // 判空
        if (input == null || input.isBlank()) {
            return Intent.UNKNOWN;
        }

        // 构建 system prompt（含历史上下文，需在外面拼接避免破坏 JSON）
        String historyPart = (historyContext == null || historyContext.isBlank())
                ? ""
                : "以下是最近的对话历史：\n" + historyContext + "\n\n";
        String systemContent = historyPart + "结合上面的对话历史（如果有），判断用户最新一条消息的意图。只返回一个词：CHAT（聊天）、GENERATE_IMAGE（生成图片）、WEATHER（查天气）、TTS（语音）、INT_SEARCH（需要联网搜索当前信息，如查询最新新闻、实时股价、今日热点等需要实时数据的问题）、UNKNOWN（无法判断）。不要返回其他内容。";

        // 1. 构造请求体（DeepSeek 兼容 OpenAI 格式）
        String requestBody = """
        {
            "model": "deepseek-v4-flash",
            "messages": [
                {
                    "role": "system",
                    "content": "%s"
                },
                {
                    "role": "user",
                    "content": "%s"
                }
            ]
        }
        """.formatted(
                systemContent.replace("\"", "\\\"").replace("\n", "\\n"),
                input.replace("\"", "\\\""));

        // 2. 发请求
        String responseJson = restClient.post()
                .uri("/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.info("DeepSeek 原始响应: {}", responseJson);

        try {
            // 用 Jackson 解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);

            // 提取 choices[0].message.content
            String intentStr = root.path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText()
                    .trim();

            log.info("DeepSeek 意图识别结果: {}", intentStr);

            // 转成 Intent 枚举并返回
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
            return Intent.UNKNOWN;  // 解析失败时兜底
        }
    }

}
