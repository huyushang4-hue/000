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
 * B站收藏夹抓取工具：抓取指定收藏夹的视频信息（含字幕）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavFetchTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_fetch",
                "抓取B站收藏夹内的视频信息。先用 bili_fav_list 获取收藏夹ID，再调用此工具抓取。mode=meta速度快（只有标题），mode=full含字幕（速度慢）",
                """
                {"type":"object","properties":{
                    "fav_id":{"type":"integer","description":"收藏夹ID，从 bili_fav_list 结果中获取"},
                    "mode":{"type":"string","enum":["meta","full"],"description":"meta=只抓标题简介（快），full=含字幕（慢）"}
                },"required":["fav_id"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        long favId = args.get("fav_id").getAsLong();
        String mode = args.has("mode") ? args.get("mode").getAsString() : "meta";

        log.info("Tool: bili_fav_fetch, favId={}, mode={}", favId, mode);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            if ("full".equals(mode)) {
                int count = biliService.fetchFavWithSubtitle(openId, favId);
                return String.format("✅ 已抓取 %d 个视频（含字幕），数据已保存。可以用 bili_fav_search 搜索视频内容。", count);
            }
            // meta 模式：返回真实视频清单，供 AI 如实回复
            List<BiliVideo> videos = biliService.fetchFavMeta(openId, favId);
            if (videos.isEmpty()) return "收藏夹中没有视频或抓取全部失败。";

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已抓取 ").append(videos.size()).append(" 个视频（标题+简介）：\n\n");
            for (int i = 0; i < videos.size(); i++) {
                BiliVideo v = videos.get(i);
                String tname = v.getTname() != null ? v.getTname() : "未知分类";
                String duration = formatDuration(v.getDuration());
                String desc = v.getDescription() != null && !v.getDescription().isBlank()
                        ? v.getDescription().substring(0, Math.min(v.getDescription().length(), 60)) : "";
                sb.append(String.format("%d. 【%s】%s (UP: %s, 时长: %s, BV: %s)%s\n",
                        i + 1, tname, v.getTitle(),
                        v.getUpName() != null ? v.getUpName() : "未知",
                        duration, v.getBvId(),
                        desc.isBlank() ? "" : "\n   简介: " + desc));
            }
            sb.append("\n以上视频已存入本地数据库，可用 bili_fav_search 搜索或 bili_fav_subtitle 查看字幕。");
            return sb.toString();
        } catch (Exception e) {
            log.error("bili_fav_fetch 失败", e);
            return "抓取失败：" + e.getMessage();
        }
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%d:%02d", min, sec);
    }
}
