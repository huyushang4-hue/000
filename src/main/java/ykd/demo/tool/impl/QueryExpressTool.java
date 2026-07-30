package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;

/**
 * 快递查询工具：调用快递100 API，根据单号查询物流轨迹。
 */
@Slf4j
@Component
public class QueryExpressTool implements Tool {

    private static final String API_URL = "https://poll.kuaidi100.com/poll/query.do";
    private static final String KEY = "oYhGrVWi4876";
    private static final String CUSTOMER = "6C1E82D33A4AE54F0266E580BD730467";
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public FunctionDefinition getDefinition() {
        return func("query_express", "查询快递物流信息。用户提供快递单号时调用。可自动识别快递公司，也可指定company参数", """
            {"type":"object","properties":{"number":{"type":"string","description":"快递单号"},"company":{"type":"string","description":"快递公司编码（可选，不填则自动识别）"}},"required":["number"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        try {
            String number = args.get("number").getAsString();
            String company = args.has("company") ? args.get("company").getAsString() : "";

            // 快递100 签名：MD5(key + number + customer).toUpperCase()
            String paramJson = "{\"com\":\"" + company + "\",\"num\":\"" + number + "\"}";
            String sign = md5Upper(paramJson + KEY + CUSTOMER);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "customer=" + CUSTOMER + "&sign=" + sign + "&param=" +
                                    java.net.URLEncoder.encode(paramJson, "UTF-8")))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String result = resp.body();

            log.info("快递查询: {} -> {} chars", number, result.length());

            // 简化返回：只取关键信息
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            if (json.has("data")) {
                StringBuilder sb = new StringBuilder("📦 快递单号: ").append(number).append("\n");
                var dataArray = json.getAsJsonArray("data");
                for (int i = 0; i < Math.min(8, dataArray.size()); i++) {
                    var item = dataArray.get(i).getAsJsonObject();
                    sb.append(item.get("time").getAsString()).append(" ")
                            .append(item.get("context").getAsString()).append("\n");
                }
                return sb.toString();
            }
            if (json.has("message")) {
                return "API返回: " + result;
            }
            return result;
        } catch (Exception e) {
            log.error("快递查询失败", e);
            return "快递查询出错: " + e.getMessage();
        }
    }

    private static String md5Upper(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
