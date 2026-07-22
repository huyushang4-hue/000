package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.SearchService;
import ykd.demo.tool.Tool;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetRealtimeInfoTool implements Tool {
    private final SearchService searchService;

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
        return searchService.search(query);
    }
}
