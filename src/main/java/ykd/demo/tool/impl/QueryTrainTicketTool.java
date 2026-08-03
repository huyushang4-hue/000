package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.tool.Tool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class QueryTrainTicketTool implements Tool {

    private final String apiKey = System.getenv("JUHE_TRAIN_API_KEY");
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public FunctionDefinition getDefinition() {
        return func("query_train_ticket", """
                查询两地之间的火车票信息，包括车次、出发时间、到达时间、历时、车型、座位类型及票价等。
                例如用户说"查一下北京到上海的火车票"、"明天从深圳到广州的高铁"
                """, """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"出发站名称，如：北京、上海虹桥、深圳北"},
                  "to":{"type":"string","description":"到达站名称，如：上海、温州南、广州南"},
                  "date":{"type":"string","description":"出发日期，格式为yyyy-MM-dd，如：2026-08-01。不填则默认明天，仅允许15天内的日期"},
                  "filter":{"type":"string","description":"车次筛选，可选值：G(高铁)、D(动车)、Z(直达特快)、T(特快)、K(快速)。不填则返回所有车次"}
                },"required":["from","to"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String from = args.has("from") ? args.get("from").getAsString() : "";
        String to = args.has("to") ? args.get("to").getAsString() : "";
        String date = args.has("date") ? args.get("date").getAsString() : "";
        String filter = args.has("filter") ? args.get("filter").getAsString() : "";

        if (apiKey == null || apiKey.isBlank()) {
            return "错误：未配置 JUHE_TRAIN_API_KEY 环境变量，请先在 https://www.juhe.cn 注册并申请火车订票查询接口";
        }
        if (from.isBlank() || to.isBlank()) {
            return "错误：请输入出发站和到达站，例如：北京到上海的火车票";
        }
        if (date.isBlank()) {
            date = LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }

        log.info("Tool: query_train_ticket, from={}, to={}, date={}, filter={}", from, to, date, filter);

        try {
            URI uri = UriComponentsBuilder.fromUriString("https://apis.juhe.cn/fapigw/train/query")
                    .queryParam("key", apiKey)
                    .queryParam("search_type", 1)
                    .queryParam("departure_station", from)
                    .queryParam("arrival_station", to)
                    .queryParam("date", date)
                    .queryParam("filter", filter.isBlank() ? "" : filter)
                    .build()
                    .encode(StandardCharsets.UTF_8)
                    .toUri();

            String json = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            int errorCode = root.has("error_code") ? root.get("error_code").asInt() : -1;

            if (errorCode != 0) {
                String reason = root.has("reason") ? root.get("reason").asText() : "未知错误";
                log.warn("火车票查询失败，errorCode={}, reason={}", errorCode, reason);
                return "查询失败：" + reason + "（错误码：" + errorCode + "）";
            }

            JsonNode result = root.get("result");
            if (result == null || !result.isArray() || result.isEmpty()) {
                return from + " 到 " + to + "（" + date + "）没有找到相关车次信息";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(from).append(" → ").append(to).append("（").append(date).append("）共 ")
                    .append(result.size()).append(" 个车次：\n\n");

            for (JsonNode train : result) {
                String trainNo = getText(train, "train_no", "未知车次");
                String depStation = getText(train, "departure_station", from);
                String arrStation = getText(train, "arrival_station", to);
                String depTime = getText(train, "departure_time", "--:--");
                String arrTime = getText(train, "arrival_time", "--:--");
                String duration = getText(train, "duration", "--:--");
                String enableBooking = getText(train, "enable_booking", "");

                sb.append("🚄 ").append(trainNo).append("\n");
                sb.append("  ").append(depStation).append(" → ").append(arrStation).append("\n");
                sb.append("  出发：").append(depTime)
                        .append("  到达：").append(arrTime)
                        .append("  历时：").append(duration).append("\n");

                if ("Y".equals(enableBooking)) {
                    sb.append("  ✅ 可预定\n");
                } else if ("N".equals(enableBooking)) {
                    sb.append("  ❌ 不可预定\n");
                }

                if (train.has("train_flags") && train.get("train_flags").isArray() && !train.get("train_flags").isEmpty()) {
                    sb.append("  标签：");
                    for (JsonNode flag : train.get("train_flags")) {
                        sb.append(flag.asText()).append(" ");
                    }
                    sb.append("\n");
                }

                if (train.has("prices") && train.get("prices").isArray()) {
                    JsonNode prices = train.get("prices");
                    sb.append("  票价：");
                    for (JsonNode price : prices) {
                        String seatType = getText(price, "seat_type", "");
                        String priceVal = getText(price, "price", "");
                        if (!seatType.isBlank() && !priceVal.isBlank()) {
                            sb.append(seatType).append("¥").append(priceVal).append(" ");
                        }
                    }
                    sb.append("\n");
                }

                sb.append("\n");
            }

            log.info("火车票查询成功，结果数={}", result.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("火车票API调用失败", e);
            return "错误：网络连接失败或API异常，请稍后重试";
        }
    }

    private String getText(JsonNode node, String field, String defaultVal) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText() : defaultVal;
    }
}
