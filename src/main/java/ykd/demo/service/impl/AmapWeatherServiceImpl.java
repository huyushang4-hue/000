package ykd.demo.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ykd.demo.service.WeatherService;

@Slf4j
@Service
public class AmapWeatherServiceImpl implements WeatherService {

    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AmapWeatherServiceImpl() {
        this.apiKey = System.getenv("AMAP_API_KEY");
        this.restClient = RestClient.create();
    }

    @Override
    public String getWeather(String city) {
        if (city == null || city.isBlank()) {
            return "错误：请输入城市名，例如：weather 北京";
        }

        log.info("查询天气，city={}", city);

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
