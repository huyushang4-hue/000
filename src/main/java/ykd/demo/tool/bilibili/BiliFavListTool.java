package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

import java.util.Map;

/**
 * B站收藏夹列表工具：列出用户所有收藏夹
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavListTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_list",
                "列出用户的B站所有收藏夹。用户说「看看我的B站收藏夹」「我的收藏夹有哪些」时调用",
                """
                {"type":"object","properties":{},"required":[]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        log.info("Tool: bili_fav_list, openId={}", openId);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            Map<Long, String> folders = biliService.listFavFolders(openId);
            if (folders.isEmpty()) return "你的B站没有收藏夹";

            StringBuilder sb = new StringBuilder("你的B站收藏夹：\n\n");
            for (Map.Entry<Long, String> entry : folders.entrySet()) {
                sb.append(String.format("📁 %s (ID: %d)\n", entry.getValue(), entry.getKey()));
            }
            sb.append("\n告诉我想看哪个收藏夹，我帮你抓取视频列表。");
            return sb.toString();
        } catch (Exception e) {
            log.error("bili_fav_list 失败", e);
            return "获取收藏夹失败：" + e.getMessage();
        }
    }
}
