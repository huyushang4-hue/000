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
 * B站退出登录工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliLogoutTool implements Tool {

    private final BiliService biliService;

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_logout",
                "退出B站登录。用户说「退出B站」「断开B站」时调用",
                """
                {"type":"object","properties":{},"required":[]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        log.info("Tool: bili_logout, openId={}", openId);
        biliService.clearLogin(openId);
        return "已退出B站登录。";
    }
}
