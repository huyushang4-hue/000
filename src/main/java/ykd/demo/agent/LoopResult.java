package ykd.demo.agent;

import lombok.Builder;
import lombok.Data;

/** Agent Loop 单轮执行结果 */
@Data
@Builder
public class LoopResult {
    /** 最终回复文本 */
    private String reply;
    /** 本轮是否完成目标 */
    private boolean goalAchieved;
    /** 执行的工具调用次数 */
    private int toolCalls;
    /** 消耗的 Token 数 */
    private int tokensUsed;
    /** 错误分类（如有） */
    private ErrorCategory errorCategory;
    /** 错误信息 */
    private String errorMessage;
    /** 是否需要人工介入 */
    private boolean humanInterventionRequired;

    public static LoopResult success(String reply, int toolCalls, int tokensUsed) {
        return LoopResult.builder()
                .reply(reply).goalAchieved(true)
                .toolCalls(toolCalls).tokensUsed(tokensUsed).build();
    }

    public static LoopResult failed(ErrorCategory category, String msg) {
        return LoopResult.builder()
                .reply("执行失败：" + msg)
                .errorCategory(category).errorMessage(msg)
                .humanInterventionRequired(category == ErrorCategory.AUTH_REQUIRED
                        || category == ErrorCategory.FATAL)
                .build();
    }

    public static LoopResult degraded(String fallbackReply) {
        return LoopResult.builder()
                .reply(fallbackReply)
                .errorCategory(ErrorCategory.DEGRADABLE)
                .build();
    }
}
