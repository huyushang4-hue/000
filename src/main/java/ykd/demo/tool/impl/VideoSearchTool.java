package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * VideoSearchTool — 视频搜索（B站官方搜索 API）。
 * <p>
 * - platform=all（默认）：先搜 Bilibili，YouTube/抖音 走 Web 搜索兜底
 * - platform=bilibili / youtube / douyin：限定平台
 * - 如果关键词里包含 "B站/bilibili/油管/youtube/抖音/douyin" 等词，自动识别平台
 */
@Slf4j
@Component
public class VideoSearchTool implements Tool {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String BILI_API = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public FunctionDefinition getDefinition() {
        return func(
                "search_video",
                "搜索视频，支持多平台(B站/YouTube/抖音)。指定 platform 限定平台，不指定则全平台搜索",
                """
                {"type":"object","properties":{
                    "keyword":{"type":"string","description":"搜索关键词"},
                    "platform":{"type":"string","description":"平台: bilibili / youtube / douyin / all（默认all）","default":"all"},
                    "page":{"type":"integer","description":"页码","default":1}
                },"required":["keyword"]}
                """
        );
    }

    @Override
    public String execute(JsonObject args) {
        String keyword = args.has("keyword") ? args.get("keyword").getAsString() : "";
        String platform = args.has("platform") ? args.get("platform").getAsString().toLowerCase() : "all";
        int page = args.has("page") ? args.get("page").getAsInt() : 1;
        if (page < 1) page = 1;

        if (keyword.isBlank()) return "请提供搜索关键词";

        // ── 智能平台识别：从关键词里检测平台意图 ──
        String kw = keyword.toLowerCase();
        if ("all".equals(platform)) {
            if (kw.contains("b站") || kw.contains("bilibili") || kw.contains("哔哩哔哩")) {
                platform = "bilibili";
            } else if (kw.contains("油管") || kw.contains("youtube") || kw.contains("ytb")) {
                platform = "youtube";
            } else if (kw.contains("抖音") || kw.contains("douyin") || kw.contains("dy")) {
                platform = "douyin";
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("🔍 视频搜索结果：「").append(keyword).append("」\n\n");

        // ── Bilibili：直连官方搜索 API ──
        if ("all".equals(platform) || "bilibili".equals(platform)) {
            try {
                String bili = searchBilibili(keyword, page);
                if (bili != null) {
                    result.append("━━━ Bilibili ━━━\n").append(bili).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("[VideoSearchTool] Bilibili 搜索异常", e);
            }
        }

        // ── YouTube / 抖音 → Web 搜索兜底（提示 AI 用 get_realtime_info 补搜） ──
        if ("all".equals(platform) || "youtube".equals(platform) || "douyin".equals(platform)) {
            try {
                String webResult = searchViaWeb(keyword, platform);
                if (webResult != null) {
                    String header = "youtube".equals(platform) ? "YouTube" : "抖音";
                    result.append("━━━ ").append(header).append(" ━━━\n").append(webResult).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("[VideoSearchTool] " + platform + " 网页搜索异常", e);
            }
        }

        String finalResult = result.toString().trim();
        if (finalResult.equals("🔍 视频搜索结果：「" + keyword + "」")) {
            return "未找到相关视频，请尝试其他关键词";
        }
        return finalResult;
    }

    // ==================== Bilibili API ====================

    private String searchBilibili(String keyword, int page) throws Exception {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = BILI_API + encoded + "&page=" + page;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.has("code") && root.get("code").getAsInt() != 0) return null;

        JsonObject data = root.getAsJsonObject("data");
        if (data == null) return null;

        // 新版 B 站搜索 API：result 是扁平数组，每个元素直接是视频对象（含 type 字段）
        JsonArray results = data.getAsJsonArray("result");
        if (results == null || results.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (int i = 0; i < results.size() && count < 5; i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            // 只取视频类型条目（结果里可能混有用户/番剧等类型）
            if (!"video".equals(item.get("type").getAsString())) continue;

            String title = item.get("title").getAsString()
                    .replace("<em class=\"keyword\">", "")
                    .replace("</em>", "");
            String author = item.get("author").getAsString();
            long play = item.get("play").getAsLong();
            String dur = item.get("duration").getAsString();  // B站返回 "MM:SS" 字符串
            String link = item.has("arcurl")
                    ? item.get("arcurl").getAsString()
                    : "https://www.bilibili.com/video/" + item.get("bvid").getAsString();

            count++;
            sb.append(count).append(". 「").append(title).append("」\n");
            sb.append("   UP主: ").append(author);
            sb.append(" | 播放: ").append(fmtCount(play));
            sb.append(" | 时长: ").append(dur);
            sb.append("\n   链接: ").append(link).append("\n\n");
        }

        return count > 0 ? sb.toString().trim() : null;
    }

    // ==================== 网页搜索兜底（YouTube/抖音） ====================

    private String searchViaWeb(String keyword, String platform) {
        String site;
        if ("youtube".equals(platform)) site = "site:youtube.com";
        else if ("douyin".equals(platform)) site = "site:douyin.com";
        else site = "site:youtube.com OR site:douyin.com";

        return "如需从 " + platform + " 获取视频，请使用 get_realtime_info 工具搜索「" + keyword + " " + site + "」。";
    }

    // ==================== 工具方法 ====================

    private static String fmtCount(long count) {
        if (count >= 10000) {
            return String.format("%.1f万", count / 10000.0);
        }
        return String.valueOf(count);
    }
}
