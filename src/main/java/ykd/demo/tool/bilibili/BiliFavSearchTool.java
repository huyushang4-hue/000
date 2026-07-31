package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.model.BiliVideo;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

import java.util.List;

/**
 * B站收藏夹搜索工具：在已抓取的视频中搜索关键词
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavSearchTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_search",
                "在B站收藏夹已抓取的视频中搜索关键词。先用 bili_fav_fetch 抓取视频，再用此工具搜索",
                """
                {"type":"object","properties":{
                    "keyword":{"type":"string","description":"搜索关键词"}
                },"required":["keyword"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        String keyword = args.get("keyword").getAsString();
        log.info("Tool: bili_fav_search, keyword={}", keyword);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            List<BiliVideo> allVideos = biliService.listAllVideos(openId);
            if (allVideos.isEmpty()) {
                return "还没有抓取过收藏夹数据。请先用 bili_fav_list 查看收藏夹，再用 bili_fav_fetch 抓取视频。";
            }

            String kw = keyword.toLowerCase();
            List<BiliVideo> matched = allVideos.stream()
                    .filter(v -> v.getTitle().toLowerCase().contains(kw)
                            || (v.getDescription() != null && v.getDescription().toLowerCase().contains(kw))
                            || (v.getUpName() != null && v.getUpName().toLowerCase().contains(kw)))
                    .toList();

            if (matched.isEmpty()) {
                return String.format("在收藏夹中没找到包含「%s」的视频。共 %d 个视频，试试其他关键词？", keyword, allVideos.size());
            }

            StringBuilder sb = new StringBuilder(String.format("找到 %d 个相关视频：\n\n", matched.size()));
            for (int i = 0; i < Math.min(matched.size(), 10); i++) {
                BiliVideo v = matched.get(i);
                String duration = v.getDuration() != null ? formatDuration(v.getDuration()) : "未知";
                sb.append(String.format("%d. 【%s】%s\n   UP主: %s | 时长: %s | BV号: %s\n\n",
                        i + 1, v.getTname() != null ? v.getTname() : "未知", v.getTitle(),
                        v.getUpName() != null ? v.getUpName() : "未知", duration, v.getBvId()));
            }

            if (matched.size() > 10) {
                sb.append(String.format("...还有 %d 个结果未显示\n", matched.size() - 10));
            }

            sb.append("告诉我视频序号，我可以帮你查看字幕内容。");
            return sb.toString();
        } catch (Exception e) {
            log.error("bili_fav_search 失败", e);
            return "搜索失败：" + e.getMessage();
        }
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%d:%02d", min, sec);
    }
}
