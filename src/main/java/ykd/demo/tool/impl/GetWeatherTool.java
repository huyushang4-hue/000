package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.demo.tool.Tool;

/** 高德天气查询工具：内联 API 调用逻辑，不再依赖外部 WeatherService */
@Slf4j
@Component
public class GetWeatherTool implements Tool {

    private final String apiKey = System.getenv("AMAP_API_KEY");
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public FunctionDefinition getDefinition() {
        return func("get_weather", "查询某个城市的实时天气", """
            {"type":"object","properties":{"city":{"type":"string","description":"城市名，如：北京、上海、广州"}},"required":["city"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String city = args.get("city").getAsString();
        log.info("Tool: get_weather, city={}", city);

        if (city == null || city.isBlank()) {
            return "错误：请输入城市名，例如：weather 北京";
        }

        try {
            String json = restClient.get()
                    .uri("https://restapi.amap.com/v3/weather/weatherInfo?city={city}&key={key}", city, apiKey)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            String status = root.get("status").asText();

            if (!"1".equals(status)) {
                log.warn("天气查询失败，city={}, status={}", city, status);
                return "错误：未找到城市 [" + city + "] 的天气数据";
            }

            JsonNode lives = root.get("lives");
            if (lives.isEmpty()) {
                return "错误：未找到城市 [" + city + "] 的天气数据";
            }

            JsonNode live = lives.get(0);
            String cityName = live.get("city").asText();
            String province = live.get("province").asText();
            String weather  = live.get("weather").asText();
            String temp     = live.get("temperature").asText();
            String humidity = live.get("humidity").asText();
            String wind     = live.get("winddirection").asText() + "风 " + live.get("windpower").asText();

            String result = cityName + "（" + province + "）：" + weather
                    + "，温度 " + temp + "°C"
                    + "，" + wind
                    + "，湿度 " + humidity + "%";

            log.info("天气查询成功，result={}", result);
            return result;

        } catch (Exception e) {
            log.error("天气API调用失败", e);
            return "错误：网络连接失败或API异常，请稍后重试";
        }
    }
}
