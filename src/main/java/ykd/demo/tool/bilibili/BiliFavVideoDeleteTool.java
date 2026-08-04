package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

/** 从B站收藏夹移除单个视频，不删除视频本身或整个收藏夹。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavVideoDeleteTool implements Tool {

    private static final String BVID_PATTERN = "BV[0-9A-Za-z]{10}";

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_video_delete",
                "从指定B站收藏夹移除一个视频，不会删除视频本身或整个收藏夹；操作不可恢复。先通过 bili_fav_list 和 bili_fav_fetch 获取收藏夹ID和视频BV号。",
                """
                {"type":"object","properties":{
                    "fav_id":{"type":"integer","description":"收藏夹ID，从 bili_fav_list 结果中获取"},
                    "bvid":{"type":"string","description":"要移除视频的BV号，从 bili_fav_fetch 结果中获取"}
                },"required":["fav_id","bvid"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        if (args == null || !args.has("fav_id") || !args.has("bvid")) {
            return "请提供收藏夹ID和视频BV号";
        }
        JsonElement favElement = args.get("fav_id");
        JsonElement bvidElement = args.get("bvid");
        if (favElement == null || favElement.isJsonNull() || bvidElement == null || bvidElement.isJsonNull()) {
            return "请提供有效的收藏夹ID和视频BV号";
        }

        try {
            long favId = favElement.getAsLong();
            String bvid = bvidElement.getAsString().trim();
            if (favId <= 0) return "收藏夹ID必须为正整数";
            if (!bvid.matches(BVID_PATTERN)) return "视频BV号格式不正确";

            String openId = AiSdkChatServiceImpl.getCurrentToolUser();
            if (openId == null) return "无法获取用户信息";
            if (!biliService.isLoggedIn(openId)) {
                return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
            }

            log.info("Tool: bili_fav_video_delete, favId={}, bvid={}", favId, bvid);
            boolean ok = biliService.deleteFavVideo(openId, favId, bvid);
            return ok ? "✅ 已从收藏夹移除视频 " + bvid : "移除收藏夹视频失败，请重试";
        } catch (NumberFormatException e) {
            return "收藏夹ID必须为正整数";
        } catch (Exception e) {
            log.error("bili_fav_video_delete 失败", e);
            return "移除收藏夹视频失败：" + e.getMessage();
        }
    }
}
