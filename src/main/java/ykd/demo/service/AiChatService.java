package ykd.demo.service;

import java.util.List;
import java.util.Map;

/** AI 对话服务接口 */
public interface AiChatService {
    String chat(String userMessage);
    String chatWithTools(String userMessage);
    String speechToText(byte[] audioBytes);
    List<Map.Entry<byte[], String>> drainPendingImages();
    /** 防抖批次：混合文字+图片一起处理，fromUserId 用于多用户上下文隔离 */
    String processDebounced(String fromUserId, List<Map<String, Object>> entries);
    /** 获取当前线程用户（openId）对应的数据库用户 ID，未登录返回 null */
    Long getCurrentUserId();
}