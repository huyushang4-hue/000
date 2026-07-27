package ykd.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.demo.entity.Conversation;
import ykd.demo.repository.ConversationRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;

    /** 保存一条对话（msgId 去重） */
    public Conversation saveIfNotExists(Long userId, String msgId, Conversation.MessageRole role,
                                        String content, Conversation.MessageType msgType) {
        // msgId 去重：同一条消息不重复保存
        if (msgId != null && !msgId.isBlank()) {
            if (conversationRepository.findByMsgId(msgId).isPresent()) {
                return null; // 已存在，跳过
            }
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setMsgId(msgId);
        conv.setRole(role);
        conv.setContent(content);
        conv.setMsgType(msgType);
        return conversationRepository.save(conv);
    }

    /** 查询用户最近 N 条对话（用于 AI 上下文） */
    public List<Conversation> getRecentConversations(Long userId, int limit) {
        return conversationRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId)
                .stream().limit(limit).toList();
    }

    /** 查询用户全部历史对话 */
    public List<Conversation> getUserHistory(Long userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }
}
