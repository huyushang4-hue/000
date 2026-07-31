package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

import java.util.concurrent.*;

/**
 * B站扫码登录工具：生成二维码 + 后台自动轮询扫码状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiliQRLoginTool implements Tool {

    private final BiliService biliService;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pollTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bili-qr-poll");
        t.setDaemon(true);
        return t;
    });

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_qr_login",
                "B站扫码登录。用户说「登录B站」「连接B站」时调用，会生成一个二维码页面让用户扫码",
                """
                {"type":"object","properties":{},"required":[]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        log.info("Tool: bili_qr_login, openId={}", openId);

        if (biliService.isLoggedIn(openId)) {
            return "你已经登录B站了，不需要重复登录。如需重新登录，请先调用 bili_logout 退出。";
        }

        cancelPoll(openId);

        try {
            BiliService.QRLoginState state = biliService.startQRLogin(openId);

            startPolling(openId);

            return "请在浏览器中打开以下链接扫码登录B站：\n"
                    + state.qrPageUrl + "\n\n"
                    + "扫码后在手机上点击确认，我会自动检测登录状态（最长等待5分钟）。";
        } catch (Exception e) {
            log.error("bili_qr_login 失败", e);
            return "启动扫码登录失败：" + e.getMessage();
        }
    }

    private void startPolling(String openId) {
        log.info("启动B站扫码轮询: openId={}", openId);

        ScheduledFuture<?> task = pollScheduler.scheduleAtFixedRate(() -> {
            try {
                String result = biliService.pollQRLogin(openId);
                if (result == null) {
                    log.info("轮询: 等待中, openId={}", openId);
                    return;
                }
                if ("expired".equals(result)) {
                    log.info("轮询: 二维码已过期, openId={}", openId);
                    cancelPoll(openId);
                    return;
                }
                log.info("轮询: 登录成功! openId={}", openId);
                cancelPoll(openId);
            } catch (Exception e) {
                log.error("B站轮询异常: openId={}", openId, e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        pollTasks.put(openId, task);

        pollScheduler.schedule(() -> {
            ScheduledFuture<?> t = pollTasks.remove(openId);
            if (t != null && !t.isDone()) {
                t.cancel(false);
                log.warn("B站二维码轮询超时，已取消: openId={}", openId);
            }
        }, 5, TimeUnit.MINUTES);
    }

    private void cancelPoll(String openId) {
        ScheduledFuture<?> task = pollTasks.remove(openId);
        if (task != null) task.cancel(false);
    }
}
