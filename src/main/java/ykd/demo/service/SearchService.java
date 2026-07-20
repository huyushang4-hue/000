package ykd.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class SearchService {

    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    public SearchService() {
        this.apiKey = System.getenv("TAVILY_API_KEY");
        this.restClient = RestClient.create();
    }

    /**
     * 搜索并返回结果文字
     */
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        log.info("Tavily 搜索，query={}", query);

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
            return resultText;

        } catch (Exception e) {
            log.error("Tavily 搜索失败", e);
            return "搜索失败，请稍后再试";
        }
    }
}