package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.tool.Tool;

@Slf4j
@Component
public class TranslatorTool implements Tool {

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    private final RestClient dashScopeClient = RestClient.builder().baseUrl("https://dashscope.aliyuncs.com").build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public FunctionDefinition getDefinition() {
        return func("translate_text", "将文本翻译为目标语言，支持中英日韩法等多种语言互译", """
            {"type":"object","properties":{"text":{"type":"string","description":"要翻译的文本内容"},"target_language":{"type":"string","description":"目标语言，如：English, Japanese, Korean, French, 中文"}},"required":["text","target_language"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String text = args.get("text").getAsString();
        String targetLang = args.get("target_language").getAsString();
        log.info("Tool: translate_text, target={}", targetLang);
        try {
            String prompt = "请将以下内容翻译为%s，只返回翻译结果，不要任何解释：\n%s"
                    .formatted(targetLang, text);
            String escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n");
            String body = "{\"model\":\"qwen3.7-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}"
                    .formatted(escaped);

            String json = dashScopeClient.post()
                    .uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);

            JsonNode choices = mapper.readTree(json).path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message").path("content").asText().trim();
            }
            return "翻译失败：无结果返回";
        } catch (Exception e) {
            log.error("translate_text failed", e);
            return "翻译失败，请稍后再试";
        }
    }
}
