package ykd.demo.service;

import java.util.List;
import java.util.Map;

/** AI 对话服务接口 */
public interface AiChatService {
    String chat(String userMessage);
    String chatWithTools(String userMessage);
    String chatWithImageBase64(String text, String base64Image);
    String speechToText(byte[] audioBytes);
    List<Map.Entry<byte[], String>> drainPendingImages();
}