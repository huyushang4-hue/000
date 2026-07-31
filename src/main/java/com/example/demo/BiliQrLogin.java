package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * B站 Web 端扫码登录工具
 *
 * 流程（官方 passport 接口）：
 *   1) GET  /x/passport-login/web/qrcode/generate  -> 拿 qrcode_key + 二维码内容 url
 *   2) 把 url 画成二维码，用户用 B站 App 扫
 *   3) 轮询 GET /x/passport-login/web/qrcode/poll?qrcode_key=xxx
 *        data.code:
 *          86101 = 未扫码
 *          86090 = 已扫未确认
 *          86038 = 过期
 *          0     = 成功，此时响应头 Set-Cookie 里带 SESSDATA / bili_jct / DedeUserID
 *
 * 成功后将需要的几个 Cookie 拼成 "SESSDATA=..; bili_jct=..; DedeUserID=.." 返回，
 * 直接塞进 BiliApiClient 的构造参数即可。
 */
public class BiliQrLogin {

    private static final String GEN_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15";
    private static final String REFERER = "https://www.bilibili.com/";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private volatile String qrUrl = "";

    public BiliQrLogin() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public JsonNode getUrl() throws Exception {
        // ---- 申请二维码 ----
        JsonNode genJson = doGet(GEN_URL);
        if (genJson.path("code").asInt(-1) != 0) {
            throw new RuntimeException("申请二维码失败: " + genJson.path("message").asText());
        }
        return genJson;
    }

    public String buildQr(JsonNode genJson) throws Exception {
        String qrContentUrl = genJson.path("data").path("url").asText();

        // ---- 把二维码内容打到终端（ASCII 形式，方便本地跑） ----
        this.qrUrl = qrContentUrl;
         // 启动 Web 服务，自动打开浏览器
        return startQrServer();
    }

    /**
     * 生成二维码 -> 终端打印 -> 轮询 -> 返回 Cookie 字符串
     *
     * @param maxWaitSeconds 最多等多久（建议 180，B站二维码有效期就是 180s）
     * @return 形如 "SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz"，失败抛异常
     */
    public String login(int maxWaitSeconds,JsonNode genJson) throws Exception {
        String qrcodeKey = genJson.path("data").path("qrcode_key").asText();

        // ---- 第2步：轮询 ----
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> pollResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(POLL_URL + "?qrcode_key=" + qrcodeKey))
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", REFERER)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            JsonNode pollJson = mapper.readTree(pollResp.body());
            JsonNode data = pollJson.path("data");
            int status = data.path("code").asInt(-1);

            switch (status) {
                case 86101:
                    //System.out.println("[等待] 二维码未扫描...");
                    break;
                case 86090:
                    System.out.println("[等待] 已扫描，请在手机上点击确认登录...");
                    break;
                case 86038:
                    throw new RuntimeException("二维码已过期，请重新运行程序");
                case 0:
                    // ---- 第3步：从 Set-Cookie 头抽我们要的字段 ----
                    System.out.println("[成功] 登录成功，正在提取 Cookie");
                    return extractCookie(pollResp);
                default:
                    System.out.println("[未知状态] " + pollJson);
            }

            Thread.sleep(1500); // 1.5s 轮询一次，别太猛
        }
        throw new RuntimeException("等待扫码超时（" + maxWaitSeconds + "s）");
    }


    /* ============ 内部工具 ============ */

    private JsonNode doGet(String url) throws Exception {
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return mapper.readTree(resp.body());
    }

    /**
     * 从 poll 成功响应的 Set-Cookie 头里，挑出三个字段拼成一行 Cookie
     * Set-Cookie 例子： "SESSDATA=abc; Path=/; Domain=.bilibili.com; Max-Age=..."
     */
    private String extractCookie(HttpResponse<String> resp) {
        StringBuilder sb = new StringBuilder();
        List<String> setCookies = resp.headers().allValues("Set-Cookie");
        for (String sc : setCookies) {
            String kv = sc.split(";", 2)[0]; // 只取 "KEY=VALUE" 部分
            if (kv.startsWith("SESSDATA=")
                    || kv.startsWith("bili_jct=")
                    || kv.startsWith("DedeUserID=")) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(kv);
            }
        }
        if (sb.length() == 0) {
            throw new RuntimeException("登录成功但未在响应头找到期望的 Cookie");
        }
        return sb.toString();
    }

    private String startQrServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0); // 随机端口
        int port = server.getAddress().getPort();

        server.createContext("/qr", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String html = buildHtml(qrUrl);
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.setExecutor(null);
        server.start();

//        System.out.println("==== 请用浏览器打开下面的地址扫码登录 ====");
//        System.out.println("    http://localhost:" + port + "/qr");
//        System.out.println("=========================================");
        return "    http://localhost:" + port + "/qr";
    }

    private String buildHtml(String url) {
        return """
<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8">
    <title>B站扫码登录</title>
    <script src="https://cdn.jsdelivr.net/npm/qrcodejs2@0.0.2/qrcode.min.js"></script>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            text-align: center;
            padding-top: 80px;
            background: #f6f7f8;
        }
        #qrcode {
            display: inline-block;
            padding: 20px;
            background: #fff;
            border-radius: 12px;
            box-shadow: 0 4px 20px rgba(0,0,0,.1);
        }
        p {
            margin-top: 20px;
            color: #555;
        }
    </style>
</head>
<body>
    <h2>B站扫码登录</h2>
    <div id="qrcode"></div>
    <p>请用 B站 App 扫描上方二维码</p>

    <script>
        new QRCode(document.getElementById("qrcode"), {
            text: "%s",
            width: 260,
            height: 260,
            correctLevel: QRCode.CorrectLevel.H
        });
    </script>
</body>
</html>
""".formatted(url);
    }


    /* ============ 独立运行演示 ============ */
    public static void main(String[] args) throws Exception {
        BiliQrLogin login = new BiliQrLogin();
        JsonNode url = login.getUrl();
        String s = login.buildQr(url);
        System.out.println("请扫码登录："+s);
        String cookie = login.login(180, url);
        System.out.println("\n拿到 Cookie，可直接用：");
        System.out.println("new BiliApiClient(\"" + cookie + "\");");
    }
}