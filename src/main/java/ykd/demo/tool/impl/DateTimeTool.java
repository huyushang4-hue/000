package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

@Slf4j
@Component
public class DateTimeTool implements Tool {

    @Override
    public FunctionDefinition getDefinition() {
        return func("get_datetime", "获取当前日期、时间、星期几等时间信息", """
            {"type":"object","properties":{"query":{"type":"string","enum":["date","time","datetime","weekday"],"description":"查询类型：date=当前日期, time=当前时间, datetime=日期和时间, weekday=星期几"}},"required":["query"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String query = args.get("query").getAsString();
        log.info("Tool: get_datetime, query={}", query);
        return switch (query.toLowerCase()) {
            case "date" -> "当前日期：" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "time" -> "当前时间：" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            case "weekday" -> "今天是：" + LocalDate.now().getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.CHINA);
            case "datetime" -> "当前时间：" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            default -> "当前时间：" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        };
    }
}
