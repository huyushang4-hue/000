package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 股票行情查询：调新浪财经接口，免费无需 API Key。
 */
@Slf4j
@Component
public class QueryStockTool implements Tool {

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public FunctionDefinition getDefinition() {
        return func("query_stock", "查询A股股票实时行情（价格、涨跌幅、成交量等）。用户说\"查股票\"\"贵州茅台多少钱\"时调用。code可选sh/sz前缀，如600519或sh600519", """
            {"type":"object","properties":{"code":{"type":"string","description":"股票代码，如600519、000001。自动补sh/sz前缀"}},"required":["code"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        try {
            String code = args.get("code").getAsString().trim();
            // 自动补前缀：6开头=上海，0/3开头=深圳
            if (!code.startsWith("sh") && !code.startsWith("sz")) {
                code = (code.startsWith("6") ? "sh" : "sz") + code;
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SINA_URL + code))
                    .header("Referer", "https://finance.sina.com.cn")
                    .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            if (body == null || body.isBlank() || !body.contains(code))
                return "未找到股票 " + code + "，请检查代码是否正确";

            // 解析：var hq_str_sh600519="名称,开盘,昨收,现价,最高,最低,..."
            String data = body.split("\"")[1];
            String[] fields = data.split(",");

            if (fields.length < 32) return "行情数据格式异常，请稍后重试";

            String name = fields[0];
            double open = safeDouble(fields[1]);
            double yestClose = safeDouble(fields[2]);
            double price = safeDouble(fields[3]);
            double high = safeDouble(fields[4]);
            double low = safeDouble(fields[5]);
            long volume = safeLong(fields[8]);
            double chg = price - yestClose;
            double chgPct = yestClose > 0 ? (chg / yestClose) * 100 : 0;
            String date = fields[30];
            String time = fields[31];

            String trend = chg > 0 ? "📈" : chg < 0 ? "📉" : "➡️";

            return String.format("""
                    %s %s (%s)
                    现价: %.2f   %s%.2f (%.2f%%)
                    开盘: %.2f  最高: %.2f  最低: %.2f
                    昨收: %.2f  成交量: %d手
                    更新: %s %s""",
                    trend, name, code.toUpperCase(),
                    price, chg > 0 ? "+" : "", chg, chgPct,
                    open, high, low,
                    yestClose, volume,
                    date, time);
        } catch (Exception e) {
            log.error("股票查询失败", e);
            return "股票查询出错: " + e.getMessage();
        }
    }

    private double safeDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }
    private long safeLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
