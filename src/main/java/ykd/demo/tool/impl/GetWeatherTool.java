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
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject cityProp = new JsonObject();
        cityProp.addProperty("type", "string");
        cityProp.addProperty("description", "城市名，如：北京、上海、广州");
        props.add("city", cityProp);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("city");
        params.add("required", required);
        return FunctionDefinition.builder()
                .name("get_weather").description("查询某个城市的实时天气").parameters(params).build();
    }

    @Override
    public String execute(JsonObject args) {
        String city = args.get("city").getAsString();
        log.info("Tool: get_weather, city={}", city);
        return weatherService.getWeather(city);
    }
}
