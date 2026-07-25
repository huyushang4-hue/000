package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.tool.Tool;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class NearbyRestaurantTool implements Tool {

    private final String apiKey = System.getenv("AMAP_API_KEY");
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 通过高德IP定位获取当前城市 */
    private String getCurrentCity() {
        try {
            String json = restClient.get()
                    .uri("https://restapi.amap.com/v3/ip?key={key}", apiKey)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json);
            if ("1".equals(root.get("status").asText())) {
                return root.get("city").asText();
            }
        } catch (Exception e) {
            log.warn("IP定位失败", e);
        }
        return null;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("search_nearby_restaurants", """
                搜索指定城市附近的美食餐厅，返回餐厅名称、评分、地址、电话等信息。
                例如用户说"推荐附近好吃的"、"深圳有什么饭馆"、"广州火锅"
                """, """
                {"type":"object","properties":{
                  "city":{"type":"string","description":"城市名，如：北京、上海、深圳、广州。不填则全国搜索"},
                  "keywords":{"type":"string","description":"搜索关键词，如：火锅、烧烤、日料、川菜。不填则默认美食"}
                },"required":[]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String city = args.has("city") ? args.get("city").getAsString() : "";
        String keywords = args.has("keywords") ? args.get("keywords").getAsString() : "";

        if (apiKey == null || apiKey.isBlank()) {
            return "错误：未配置 AMAP_API_KEY 环境变量，请先在 https://lbs.amap.com 注册并获取 Key";
        }
        if (city.isBlank()) {
            city = getCurrentCity();
            if (city == null || city.isBlank() || "[]".equals(city)) {
                return "无法获取你的位置，请直接告诉我城市名，比如：北京有什么好吃的";
            }
            log.info("IP定位城市: {}", city);
        }
        if (keywords.isBlank()) keywords = "美食";

        log.info("Tool: search_nearby_restaurants, city={}, keywords={}", city, keywords);

        try {
            String json = restClient.get()
                    .uri("https://restapi.amap.com/v3/place/text" +
                                    "?key={key}&keywords={kw}&types={types}&city={city}" +
                                    "&offset={offset}&page=1&extensions=all",
                            apiKey, keywords, "050000", city, 10)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            String status = root.get("status").asText();

            if (!"1".equals(status)) {
                log.warn("POI搜索失败, info={}", root.get("info").asText());
                return "搜索失败：" + root.get("info").asText();
            }

            JsonNode pois = root.get("pois");
            if (pois == null || pois.isEmpty()) {
                return "在「" + city + "」没有找到相关餐厅，试试换个关键词或城市";
            }

            List<String> items = new ArrayList<>();
            for (JsonNode poi : pois) {
                String name = poi.get("name").asText();
                String address = poi.has("address") && !poi.get("address").isNull()
                        ? poi.get("address").asText() : "地址未提供";
                String tel = poi.has("tel") && !poi.get("tel").isNull()
                        ? poi.get("tel").asText() : "暂无电话";
                String rating = poi.has("biz_ext") && poi.get("biz_ext").has("rating")
                        ? poi.get("biz_ext").get("rating").asText() : "暂无评分";
                String cost = "暂无";
                if (poi.has("biz_ext") && poi.get("biz_ext").has("cost")
                        && poi.get("biz_ext").get("cost").isArray()
                        && poi.get("biz_ext").get("cost").size() > 0) {
                    cost = poi.get("biz_ext").get("cost").get(0).asText();
                }
                String shopHours = poi.has("shopinfo") && poi.get("shopinfo").has("shop_hours")
                        ? poi.get("shopinfo").get("shop_hours").asText() : "";

                StringBuilder sb = new StringBuilder();
                sb.append("【").append(name).append("】");
                if (!"暂无评分".equals(rating)) {
                    sb.append(" ⭐").append(rating);
                }
                if (!"暂无".equals(cost)) {
                    sb.append(" 人均¥").append(cost);
                }
                sb.append("\n  📍").append(address);
                sb.append("  📞").append(tel);
                if (!shopHours.isBlank()) {
                    sb.append("  🕐").append(shopHours);
                }
                items.add(sb.toString());
            }

            StringBuilder result = new StringBuilder();
            result.append("在「").append(city).append("」找到以下餐厅（搜索：").append(keywords).append("）：\n\n");
            for (int i = 0; i < items.size(); i++) {
                result.append(i + 1).append(". ").append(items.get(i)).append("\n\n");
            }
            result.append("可以告诉我想去哪家，我帮你查更多信息～");

            log.info("POI搜索成功，结果数={}", items.size());
            return result.toString();

        } catch (Exception e) {
            log.error("POI搜索API调用失败", e);
            return "错误：网络连接失败或API异常，请稍后重试";
        }
    }
}
