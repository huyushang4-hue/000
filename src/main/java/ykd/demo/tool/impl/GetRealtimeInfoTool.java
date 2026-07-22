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
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");
        queryProp.addProperty("description", "搜索关键词");
        props.add("query", queryProp);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("query");
        params.add("required", required);
        return FunctionDefinition.builder()
                .name("get_realtime_info").description("搜索实时信息，如新闻、股价、热点").parameters(params).build();
    }

    @Override
    public String execute(JsonObject args) {
        String query = args.get("query").getAsString();
        log.info("Tool: get_realtime_info, query={}", query);
        return searchService.search(query);
    }
}
