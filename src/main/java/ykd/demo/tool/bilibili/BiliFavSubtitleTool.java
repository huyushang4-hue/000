package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

/**
 * B站视频字幕查看工具：查看指定视频的字幕内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavSubtitleTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_subtitle",
                "查看B站收藏夹中某个视频的字幕内容。先用 bili_fav_search 找到视频BV号，再用此工具查看字幕",
                """
                {"type":"object","properties":{
                    "bv_id":{"type":"string","description":"视频BV号，如 BV1xx411c7mD"}
                },"required":["bv_id"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        String bvId = args.get("bv_id").getAsString();
        log.info("Tool: bili_fav_subtitle, bvId={}", bvId);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            String subtitle = biliService.getSubtitle(openId, bvId);
            if (subtitle == null || subtitle.isBlank()) {
                return String.format("视频 %s 没有字幕数据。可能需要先用 bili_fav_fetch 的 mode=full 重新抓取。", bvId);
            }

            // 截断过长的字幕
            if (subtitle.length() > 3000) {
                subtitle = subtitle.substring(0, 3000) + "\n\n[... 字幕已截断，共 " + subtitle.length() + " 字符]";
            }

            return String.format("视频 %s 的字幕内容：\n\n%s", bvId, subtitle);
        } catch (Exception e) {
            log.error("bili_fav_subtitle 失败", e);
            return "获取字幕失败：" + e.getMessage();
        }
    }
}
