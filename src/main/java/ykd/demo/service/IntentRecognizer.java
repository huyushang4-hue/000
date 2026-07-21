package ykd.demo.service;

import ykd.demo.intent.Intent;

/**
 * 用户意图识别接口
 */
public interface IntentRecognizer {
    Intent recognize(String input, String historyContext);
}