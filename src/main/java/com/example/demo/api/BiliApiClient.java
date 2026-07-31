package com.example.demo.api;

import com.example.demo.model.BiliVideo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B站API客户端
 * 参考接口文档：https://github.com/SocialSisterYi/bilibili-API-collect
 */
public class BiliApiClient {

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String API_BASE = "https://api.bilibili.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String cookie;

    public BiliApiClient(String cookie) {
        this.cookie = cookie;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发送GET请求
     */
    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", cookie)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("B站API请求失败, code=" + code
                    + ", message=" + root.path("message").asText("unknown"));
        }
        return root.path("data");
    }

    /**
     * 获取用户创建的所有收藏夹
     */
    public Map<Long, String> listFavFolders(long mid) throws Exception {
        String url = API_BASE + "/x/v3/fav/folder/created/list-all?up_mid=" + mid;
        JsonNode data = get(url);
        JsonNode list = data.path("list");

        Map<Long, String> folders = new HashMap<>();
        for (JsonNode item : list) {
            long id = item.path("id").asLong();
            String title = item.path("title").asText();
            folders.put(id, title);
        }
        return folders;
    }

    /**
     * 获取收藏夹内的视频列表（分页拉取全部）
     */
    public List<BiliVideo> listFavVideos(long mediaId) throws Exception {
        List<BiliVideo> allVideos = new ArrayList<>();
        int pn = 1;
        int ps = 20;

        while (true) {
            String url = String.format("%s/x/v3/fav/resource/list?media_id=%d&pn=%d&ps=%d&platform=web",
                    API_BASE, mediaId, pn, ps);
            JsonNode data = get(url);
            JsonNode medias = data.path("medias");

            if (medias.isEmpty() || medias.isNull()) {
                break;
            }

            for (JsonNode m : medias) {
                BiliVideo video = new BiliVideo();
                video.setBvId(m.path("bvid").asText());
                video.setTitle(m.path("title").asText());
                video.setDuration(m.path("duration").asInt());
                video.setFavId(mediaId);

                JsonNode upper = m.path("upper");
                if (!upper.isMissingNode()) {
                    video.setUpName(upper.path("name").asText());
                    video.setUpMid(upper.path("mid").asLong());
                }
                allVideos.add(video);
            }

            boolean hasMore = data.path("has_more").asBoolean(false);
            if (!hasMore) {
                break;
            }
            pn++;
        }
        return allVideos;
    }

    /**
     * 获取视频详细信息（包含简介、cid、aid）
     */
    public BiliVideo getVideoDetail(String bvid) throws Exception {
        String url = API_BASE + "/x/web-interface/view?bvid=" + bvid;
        JsonNode data = get(url);

        BiliVideo video = new BiliVideo();
        video.setBvId(data.path("bvid").asText());
        video.setTitle(data.path("title").asText());
        video.setDescription(data.path("desc").asText());
        video.setDuration(data.path("duration").asInt());
        video.setTname(data.path("tname").asText());

        JsonNode owner = data.path("owner");
        video.setUpName(owner.path("name").asText());
        video.setUpMid(owner.path("mid").asLong());

        // 取第一P的cid，favId字段暂存cid
        JsonNode pages = data.path("pages");
        if (pages.isArray() && pages.size() > 0) {
            long cid = pages.get(0).path("cid").asLong();
            video.setFavId(cid);
        }
        return video;
    }

    /**
     * 获取视频aid（AV号）
     */
    public long getAid(String bvid) throws Exception {
        String url = API_BASE + "/x/web-interface/view?bvid=" + bvid;
        JsonNode data = get(url);
        return data.path("aid").asLong();
    }

    /**
     * 获取视频字幕的下载地址
     * 优先选中文（zh-CN），没有则返回第一个
     * @return 字幕JSON文件的URL，没有字幕返回null
     */
    public String getSubtitleUrl(long aid, long cid) throws Exception {
        String url = String.format("%s/x/player/v2?aid=%d&cid=%d", API_BASE, aid, cid);
        JsonNode data = get(url);

        JsonNode subtitles = data.path("subtitle").path("subtitles");
        if (subtitles.isMissingNode() || subtitles.isEmpty()) {
            return null;
        }

        // 优先找中文简体
        for (JsonNode sub : subtitles) {
            String lan = sub.path("lan").asText();
            if ("zh-CN".equals(lan) || "zh-Hans".equals(lan)) {
                return normalizeSubtitleUrl(sub.path("subtitle_url").asText());
            }
        }

        // 没有中文就取第一个
        return normalizeSubtitleUrl(subtitles.get(0).path("subtitle_url").asText());
    }

    /** 补全字幕 URL 协议：//xxx → https://xxx；裸路径/缺协议 → https://aisubtitle.hdslb.com + 路径 */
    private String normalizeSubtitleUrl(String subUrl) {
        if (subUrl == null || subUrl.isBlank()) return null;
        if (subUrl.startsWith("https://") || subUrl.startsWith("http://")) return subUrl;
        if (subUrl.startsWith("//")) return "https:" + subUrl;
        // 以 / 开头的相对路径：拼默认字幕域名
        return "https://aisubtitle.hdslb.com" + (subUrl.startsWith("/") ? subUrl : "/" + subUrl);
    }

    /**
     * 下载字幕JSON并转换为纯文本
     * @param subtitleUrl 字幕文件地址
     * @return 拼接好的纯文本字幕
     */
    public String downloadSubtitleText(String subtitleUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(subtitleUrl))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        JsonNode body = root.path("body");
        if (body.isMissingNode() || !body.isArray()) {
            return "";
        }

        // 把每一句字幕拼接起来
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : body) {
            String content = item.path("content").asText();
            if (content != null && !content.isBlank()) {
                sb.append(content).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 一键获取视频字幕纯文本
     * @param bvid BV号
     * @return 字幕纯文本，没有字幕返回null
     */
    public String getVideoSubtitle(String bvid) throws Exception {
        long aid = getAid(bvid);
        // 拿cid
        BiliVideo detail = getVideoDetail(bvid);
        long cid = detail.getFavId(); // favId字段暂存了cid

        String subtitleUrl = getSubtitleUrl(aid, cid);
        if (subtitleUrl == null) {
            return null;
        }
        return downloadSubtitleText(subtitleUrl);
    }
}
