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
 * B站收藏夹删除工具：删除用户的B站收藏夹（含本地缓存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliFavDeleteTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_fav_delete",
                "删除B站收藏夹。用户说「删除/移除收藏夹XX」「把XX收藏夹删了」时调用，删除后不可恢复",
                """
                {"type":"object","properties":{
                    "fav_id":{"type":"integer","description":"要删除的收藏夹ID，从 bili_fav_list 结果中获取"}
                },"required":["fav_id"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        long favId = args.get("fav_id").getAsLong();
        log.info("Tool: bili_fav_delete, favId={}", favId);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            boolean ok = biliService.deleteFavFolder(openId, favId);
            return ok ? "✅ 收藏夹已删除" : "删除收藏夹失败，请重试";
        } catch (Exception e) {
            log.error("bili_fav_delete 失败", e);
            return "删除收藏夹失败：" + e.getMessage();
        }
    }
}
