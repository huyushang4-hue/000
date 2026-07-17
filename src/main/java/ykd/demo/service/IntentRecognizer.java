package ykd.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.intent.Intent;

@Slf4j
@Service
public class IntentRecognizer {

    /**
     * 识别用户消息的意图
     * @param input 用户输入的文字
     * @return 意图类型
     */
    public Intent recognize(String input) {
        // 防御：空消息
        if (input == null || input.isBlank()) {
            return Intent.UNKNOWN;
        }

        // 转小写方便匹配
        String lower = input.trim().toLowerCase();

        // === 1. 命令：/weather ===
        if (lower.startsWith("/weather")) {
            log.info("识别意图 → WEATHER，input={}", input);
            return Intent.WEATHER;
        }

        // === 2. 生图意图 ===
        if (isGenerateImage(lower)) {
            log.info("识别意图 → GENERATE_IMAGE，input={}", input);
            return Intent.GENERATE_IMAGE;
        }

        // === 3. 兜底：普通聊天 ===
        log.info("识别意图 → CHAT，input={}", input);
        return Intent.CHAT;
    }

    /**
     * 判断是否为生图意图
     */
    private boolean isGenerateImage(String lower) {
        // "生成图片" 强关键词
        if (lower.contains("生成图片")) {
            return true;
        }

        // "画" 相关的组合
        if (lower.contains("画")) {
            // 帮我画、给我画
            if (lower.contains("帮我画") || lower.contains("给我画")) {
                return true;
            }
            // 画 + 数量词，如：画一只、画一张、画一幅、画个
            if (lower.matches(".*画[了一只张幅个条匹].*")) {
                return true;
            }
        }

        return false;
    }
}