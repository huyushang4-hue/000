package ykd.demo.service;

import com.openilink.ILinkClient;
import com.openilink.auth.LoginCallbacks;
import com.openilink.model.response.LoginResult;
import com.openilink.util.MessageHelper;
import com.openilink.monitor.MonitorOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ILinkBotService {

    private final ILinkClient client;
    private final WeatherService weatherService;    // 复用上午的
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final AiChatService aiChatService;

    public ILinkBotService(WeatherService weatherService,AiChatService aiChatService) {
        this.weatherService = weatherService;
        this.client = ILinkClient.builder().token("").build();
        this.aiChatService = aiChatService;
    }

    /** 启动：扫码登录 → 进入消息循环 */
    public void start() {
        // ① 扫码登录
        LoginResult result = client.loginWithQR(new LoginCallbacks() {
            @Override
            public void onQRCode(String qrCodeUrl) {
                log.info("请扫码登录：{}", qrCodeUrl);
                // 实际跑的时候复制这个 URL 到浏览器打开，用微信扫码
            }

            @Override
            public void onScanned() {
                log.info("已扫码，请在微信上点击确认...");
            }

            @Override
            public void onExpired(int attempt, int maxAttempts) {
                log.warn("二维码过期，正在刷新 ({}/{})", attempt, maxAttempts);
            }
        });

        if (!result.isConnected()) {
            log.error("登录失败：{}", result.getMessage());
            return;
        }
        log.info("登录成功！BotID={}", result.getBotId());

        // ② 进入消息循环（阻塞，必须开独立线程）
        MonitorOptions options = MonitorOptions.builder()
                .onError(e -> log.warn("监听错误：{}", e.getMessage()))
                .onSessionExpired(() -> log.error("会话过期，需重新扫码"))
                .build();

        client.monitor(msg -> {
            String text = MessageHelper.extractText(msg);
            if (text == null || text.isBlank()) return;

            log.info("收到消息 [{}]: {}", msg.getFromUserId(), text);

            // ③ 解析命令 + 分发
            String reply = dispatch(text);

            // ④ 回复
            client.push(msg.getFromUserId(), reply);
            log.info("回复 [{}]: {}", msg.getFromUserId(), reply);
        }, options, stopFlag);
    }

    /** 命令分发 —— 复用你上午的那套逻辑 */
    private String dispatch(String raw) {
        String[] parts = raw.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1] : "";

        return switch (command) {
            case "help"    -> "命令：help / version / status / weather <城市名>";
            case "version" -> "版本：1.0.0";
            case "status"  -> "运行中，Java 21 + Spring Boot 4.1";
            case "weather" -> argument.isBlank()
                    ? "请带城市名，如：weather 北京"
                    : weatherService.getWeather(argument);
            case "ai" -> argument.isBlank()
                    ? "请带内容，如：ai 帮我写首诗"
                    : aiChatService.chat(argument);
            default        -> "未知命令，回复 help 查看帮助";
        };
    }

    /** 停止监听 */
    public void stop() {
        stopFlag.set(true);
    }
}