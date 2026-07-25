package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.tool.Tool;

/** Tavily 联网搜索工具：内联 API 调用逻辑，不再依赖外部 SearchService */
@Slf4j
@Component
public class GetRealtimeInfoTool implements Tool {

    private final String apiKey = System.getenv("TAVILY_API_KEY");
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    @Override
    public FunctionDefinition getDefinition() {
        return func("get_realtime_info", "搜索实时信息，如新闻、股价、热点", """
            {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String query = args.get("query").getAsString();
        log.info("Tool: get_realtime_info, query={}", query);

        if (query == null || query.isBlank()) {
            return "";
        }

        try {
            String requestBody = String.format("""
                {
                    "api_key": "%s",
                    "query": "%s",
                    "search_depth": "basic",
                    "include_answer": true,
                    "max_results": 10
                }
                """, apiKey, query.replace("\"", "\\\""));

            String responseJson = restClient.post()
                    .uri(TAVILY_URL)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(responseJson);

            String answer = root.path("answer").asText();

            StringBuilder sb = new StringBuilder();
            if (answer != null && !answer.isBlank()) {
                sb.append("总结：").append(answer).append("\n\n");
            }

            sb.append("来源：\n");
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (int i = 0; i < results.size(); i++) {
                    JsonNode r = results.get(i);
                    String title = r.path("title").asText();
                    String content = r.path("content").asText();
                    sb.append(i + 1).append(". ").append(title).append("\n");
                    sb.append("   ").append(content).append("\n\n");
                }
            }

            String resultText = sb.toString();
            log.info("Tavily 搜索成功，结果长度={} 字符", resultText.length());
            log.info("Tavily 搜索结果：{}", resultText);
            return resultText;

        } catch (Exception e) {
            log.error("Tavily 搜索失败", e);
            return "搜索失败，请稍后再试";
        }
    }
}
