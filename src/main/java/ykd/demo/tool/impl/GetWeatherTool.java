package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.WeatherService;
import ykd.demo.tool.Tool;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetWeatherTool implements Tool {
    private final WeatherService weatherService;

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
        return weatherService.getWeather(city);
    }
}
