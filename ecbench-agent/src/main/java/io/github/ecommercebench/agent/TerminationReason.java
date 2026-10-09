package io.github.ecommercebench.agent;

/**
 * 一次 episode 的终止原因；端口 Python `_normalize_termination_reason` 的两类规范终态。
 *
 * <p>{@link #ENV_COMPLETED} 表示跑满全部营业日（对应引擎 "max_days"），其余均为提前终止。 {@code canonical()} 折叠成 Python
 * 管线下游期望的 {@code env_completed}/{@code env_terminated}，明细保留在 {@code terminationDetail}。
 */
public enum TerminationReason {
    ENV_COMPLETED(true),
    BANKRUPT(false),
    MAX_TURNS_REACHED(false),
    NO_TOOL_CALLS(false),
    LLM_ERROR(false);

    private final boolean completed;

    TerminationReason(boolean completed) {
        this.completed = completed;
    }

    public boolean completed() {
        return completed;
    }

    /**
     * 折叠为两类规范终态字符串。
     */
    public String canonical() {
        return completed ? "env_completed" : "env_terminated";
    }

    /**
     * 把引擎终止原因字符串映射为枚举。
     */
    public static TerminationReason fromEngineReason(String reason) {
        if ("max_days".equals(reason) || "env_completed".equals(reason)) {
            return ENV_COMPLETED;
        }
        if ("bankrupt".equals(reason) || "bankruptcy".equals(reason)) {
            return BANKRUPT;
        }
        return MAX_TURNS_REACHED;
    }
}
