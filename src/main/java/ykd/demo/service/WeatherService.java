package ykd.demo.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;                   // Lombok：编译时自动生成 log 变量
import org.springframework.stereotype.Service;      // Spring：标记"这是一个服务类，帮我管理"
import org.springframework.web.client.RestClient;   // Spring：用来发 HTTP 请求的工具

/**
 * 天气服务：调用高德天气 API 查询城市天气。
 * 用法：weatherService.getWeather("北京") → "北京市（北京）：阴，温度 30°C，东北风 ≤3，湿度 52%"
 */
@Slf4j        // ← 编译后自动生成：private static final Logger log = LoggerFactory.getLogger(...)
@Service      // ← 告诉 Spring："这个类是 Service，帮我自动创建"
public class WeatherService {

    // ---------- 三个成员变量（类的"零件"）----------

    // apiKey：高德 API 密钥，从系统环境变量读取，不写在代码里（安全）
    private final String apiKey;

    // restClient：HTTP 客户端，负责向高德服务器发 GET 请求
    private final RestClient restClient;

    // mapper：Jackson 的 JSON 解析器，把 API 返回的 JSON 字符串变成可读的树结构
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造方法：Spring 创建这个对象时自动调用。
     * 做两件事：① 读环境变量拿到 API Key  ② 创建 HTTP 客户端
     */
    public WeatherService() {
        // System.getenv("环境变量名") → 读取操作系统的环境变量
        // 你之前在 IDEA Run Configuration 里填的就是环境变量
        this.apiKey = System.getenv("A_QWEN_API_KEY");

        // RestClient.create() → 创建一个 HTTP 客户端，无需配置，直接用
        this.restClient = RestClient.create();
    }

    /**
     * 查询城市天气。
     *
     * @param city 城市名，如"北京"、"上海"、"110000"（高德 adcode 也行）
     * @return 格式化的天气字符串，或错误提示
     */
    public String getWeather(String city) {

        // ===== 第 1 步：校验输入 =====
        // city == null       → 用户直接按回车，什么都没输
        // city.isBlank()     → 用户输入了空格或空白
        // 这种情况不发 HTTP 请求，直接返回提示
        if (city == null || city.isBlank()) {
            return "错误：请输入城市名，例如：weather 北京";
        }

        // 记录日志：谁在查什么城市（出问题时从这里排查）
        log.info("查询天气，city={}", city);

        // ===== 第 2 步：发 HTTP 请求 + 解析 JSON =====
        // try-catch 兜底：网络不通、JSON 格式错、服务器报错……任何意外都不会让程序崩溃
        try {

            // --- 2.1 发送 GET 请求 ---
            // 链式调用拆解：
            //   .get()        → 我要发 GET 请求
            //   .uri(模板, 参数1, 参数2) → 拼 URL，{city}=北京、{key}=4f994225...
            //      最终 URL：https://restapi.amap.com/v3/weather/weatherInfo?city=北京&key=xxx
            //   .retrieve()   → 执行请求，拿到响应
            //   .body(String.class) → 把响应内容转成 String（一个巨大的 JSON 字符串）
            String json = restClient.get()
                    .uri("https://restapi.amap.com/v3/weather/weatherInfo?city={city}&key={key}", city, apiKey)
                    .retrieve()
                    .body(String.class);

            // --- 2.2 解析 JSON ---
            // 高德返回的 JSON 长这样（压缩版）：
            // {"status":"1","lives":[{"city":"北京市","weather":"阴","temperature":"30",...}]}

            // mapper.readTree(json) → JSON 字符串 → JsonNode 树
            JsonNode root = mapper.readTree(json);

            // root.get("status") → 取 status 字段
            // .asText() → 转成 String
            // 高德 API：成功="1"，失败="0"
            String status = root.get("status").asText();

            // --- 2.3 判断 API 是否成功 ---
            if (!"1".equals(status)) {
                log.warn("天气查询失败，city={}, status={}", city, status);
                return "错误：未找到城市 [" + city + "]，请检查城市名";
            }

            // root.get("lives") → 取天气数据数组
            JsonNode lives = root.get("lives");

            // 数组为空 = 没有数据（城市名不存在）
            if (lives.isEmpty()) {
                return "错误：未找到城市 [" + city + "] 的天气数据";
            }

            // --- 2.4 逐字段提取 ---
            // lives.get(0) → 数组第一个元素（通常就一个）
            JsonNode live = lives.get(0);

            // 每个 .get("字段名").asText() → 取出值 → 转 String
            String cityName = live.get("city").asText();              // "北京市"
            String province = live.get("province").asText();          // "北京"
            String weather  = live.get("weather").asText();           // "阴"
            String temp     = live.get("temperature").asText();       // "30"
            String humidity = live.get("humidity").asText();          // "52"
            String wind     = live.get("winddirection").asText()      // "东北"
                    + "风 "
                    + live.get("windpower").asText();         // "≤3"

            // --- 2.5 拼成可读字符串 ---
            String result = cityName + "（" + province + "）：" + weather
                    + "，温度 " + temp + "°C"
                    + "，" + wind
                    + "，湿度 " + humidity + "%";

            // 记录成功日志
            log.info("天气查询成功，result={}", result);
            return result;   // 返回给 CliRunner 打印

        } catch (Exception e) {
            // ===== 第 3 步：兜底 =====
            // 能走到这里的情况：网络断、DNS 失败、API 服务器挂了、JSON 格式变了……
            // log.error("描述", e) → 完整堆栈写入日志，方便排查
            log.error("天气API调用失败", e);
            // 给用户看的简短提示，不暴露代码细节
            return "错误：网络连接失败或API异常，请稍后重试";
        }
    }
}