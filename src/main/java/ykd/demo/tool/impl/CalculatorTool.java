package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

@Slf4j
@Component
public class CalculatorTool implements Tool {

    @Override
    public FunctionDefinition getDefinition() {
        return func("calculator", "执行数学计算表达式，支持加减乘除、括号、幂运算(^)、开方(sqrt)、绝对值(abs)", """
            {"type":"object","properties":{"expression":{"type":"string","description":"数学表达式，如：(1+2)*3、sqrt(16)、2^10"}},"required":["expression"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String expression = args.get("expression").getAsString();
        log.info("Tool: calculator, expression={}", expression);
        try {
            String sanitized = expression
                    .replace("^", "**")
                    .replace("sqrt(", "Math.sqrt(")
                    .replace("abs(", "Math.abs(");

            ScriptEngine engine = new ScriptEngineManager().getEngineByName("js");
            if (engine != null) {
                Object result = engine.eval(sanitized);
                return formatResult(expression, result);
            }
            return evaluateSimple(expression);
        } catch (Exception e) {
            log.error("calculator failed", e);
            return "计算失败：" + e.getMessage();
        }
    }

    private String formatResult(String expr, Object result) {
        if (result instanceof Number num) {
            double d = num.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return expr + " = " + (long) d;
            }
            return expr + " = " + d;
        }
        return expr + " = " + result;
    }

    private String evaluateSimple(String expr) {
        String cleaned = expr.replaceAll("[^0-9+\\-*/.() ]", "");
        if (cleaned.isBlank()) return "无法解析表达式";
        double result = evalExpr(cleaned.replaceAll("\\s+", ""));
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            return cleaned + " = " + (long) result;
        }
        return cleaned + " = " + result;
    }

    private double evalExpr(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == ')') depth++;
            if (s.charAt(i) == '(') depth--;
            if (depth == 0 && (s.charAt(i) == '+' || s.charAt(i) == '-') && i > 0) {
                double l = evalExpr(s.substring(0, i));
                double r = evalExpr(s.substring(i + 1));
                return s.charAt(i) == '+' ? l + r : l - r;
            }
        }
        depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == ')') depth++;
            if (s.charAt(i) == '(') depth--;
            if (depth == 0 && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                double l = evalExpr(s.substring(0, i));
                double r = evalExpr(s.substring(i + 1));
                return s.charAt(i) == '*' ? l * r : l / r;
            }
        }
        if (s.startsWith("(") && s.endsWith(")")) {
            return evalExpr(s.substring(1, s.length() - 1));
        }
        return Double.parseDouble(s);
    }
}
