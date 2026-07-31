package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.entity.Conversation;
import ykd.demo.repository.UserRepository;
import ykd.demo.repository.ConversationRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索增强服务：从历史对话中搜索相关上下文，注入到 prompt 中。
 * 不需要 pgvector，用 MySQL LIKE 做关键词匹配即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final ConversationRepository conversationRepo;
    private final UserRepository userRepo;

    /**
     * 根据用户消息搜索相似历史对话，返回格式化的上下文文本。
     * @param userId 当前用户ID
     * @param query 用户当前消息
     * @param maxResults 最多返回几条
     * @return 格式化的历史上下文，没找到则返回空字符串
     */
    public String searchContext(String openId, String query, int maxResults) {
        if (query == null || query.isBlank() || openId == null) return "";

        // openId → userId
        Long userId = userRepo.findByOpenId(openId).map(ykd.demo.entity.User::getId).orElse(null);
        if (userId == null) return "";

        // 从用户消息中提取关键词（简单分词：取2字以上）
        String[] words = query.split("[\\s，。！？,.!?]+");
        List<String> keywords = java.util.Arrays.stream(words)
                .filter(w -> w.length() >= 2)
                .limit(5)
                .collect(Collectors.toList());
        if (keywords.isEmpty()) return "";

        // 用第一个关键词搜索历史对话
        String keyword = String.join(" ", keywords);  // MATCH AGAINST 不需要 % 通配符
        List<Conversation> results = conversationRepo
                .searchByKeyword(userId, "USER", keyword);

        if (results.isEmpty()) return "";

        // 格式化：每条前加时间戳和角色
        StringBuilder sb = new StringBuilder();
        sb.append("\n# 历史相关对话（来自数据库，仅供参考）：\n");
        int count = 0;
        for (Conversation c : results) {
            if (count >= maxResults) break;
            // 只取内容，不要过长
            String content = c.getContent();
            if (content.length() > 200) content = content.substring(0, 200) + "...";
            sb.append("- [").append(c.getCreatedAt()).append("] 用户: ").append(content).append("\n");
            count++;
        }
        return sb.toString();
    }
}
