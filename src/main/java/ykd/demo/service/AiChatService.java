package ykd.demo.service;

import java.util.List;
import java.util.Map;

/**
 * AI 对话服务接口 —— 所有 AI 能力都从这里走
 */
public interface AiChatService {
    String chat(String userMessage);
    String chatWithTools(String userMessage);
    String chatWithSearch(String userMessage);
    String chatWithImage(String text, String imageUrl);
    String chatWithImageBase64(String text, String base64Image);
    String generateImage(String prompt);
    byte[] textToSpeech(String text);
    String speechToText(byte[] audioBytes);
    String setVoice(String name);
    String getCurrentVoice();
    String getRecentHistory(int count);
    String getLastUsage();
    /** 获取并清空积压的待发送图片列表，每个元素是 [图片字节, 文件名] */
    List<Map.Entry<byte[], String>> drainPendingImages();
}