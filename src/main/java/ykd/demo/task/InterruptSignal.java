package ykd.demo.task;

/**
 * ThreadLocal 中断信号：doToolLoop 检测到 ask_user 或 create_plan 时设置，
 * 由 TaskOrchestratorService 在 AI 调用完成后检查，决定下一步动作。
 *
 * 关键：信号由 doToolLoop 在主线程（parallel join 之后）设置，因此
 * 调用方（同一线程上的编排器）可以直接通过 {@code get()} 获取。
 */
public class InterruptSignal {

    public enum Type {
        /** ask_user 被调用，需要向用户提问 */
        CLARIFY,
        /** create_plan 被调用，任务拆解为多步骤 */
        PLAN
    }

    private final Type type;
    private final String data;

    private static final ThreadLocal<InterruptSignal> current = new ThreadLocal<>();

    public InterruptSignal(Type type, String data) {
        this.type = type;
        this.data = data;
    }

    public static void set(Type type, String data) {
        current.set(new InterruptSignal(type, data));
    }

    public static InterruptSignal get() {
        return current.get();
    }

    public static void clear() {
        current.remove();
    }

    public Type getType() { return type; }
    public String getData() { return data; }
}
