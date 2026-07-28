package ykd.demo.agent;

/** Agent Loop 错误分类 */
public enum ErrorCategory {

    /** 瞬时错误：网络超时、临时不可用 → 自动重试 */
    RETRYABLE,

    /** 认证错误：需要登录/授权 → 暂停等人工介入 */
    AUTH_REQUIRED,

    /** 可降级：写入失败可以降级处理（如存草稿） */
    DEGRADABLE,

    /** 致命错误：权限不足、数据损坏 → 通知用户，保存现场 */
    FATAL;

    /** 根据异常类型自动分类 */
    public static ErrorCategory classify(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String name = e.getClass().getSimpleName().toLowerCase();

        // 网络/超时 → 可重试
        if (name.contains("timeout") || name.contains("ioexception")
                || msg.contains("timeout") || msg.contains("connection")
                || msg.contains("timed out") || msg.contains("network")) {
            return RETRYABLE;
        }

        // 认证/鉴权 → 需要人工
        if (name.contains("unauthorized") || msg.contains("unauthorized")
                || msg.contains("auth") || msg.contains("login")
                || msg.contains("403") || msg.contains("401")) {
            return AUTH_REQUIRED;
        }

        // 写入/存储失败 → 可降级
        if (msg.contains("write") || msg.contains("save")
                || msg.contains("disk") || msg.contains("storage")) {
            return DEGRADABLE;
        }

        // 默认致命
        return FATAL;
    }
}
