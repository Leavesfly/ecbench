package io.github.ecommercebench.agent;

import io.github.ecommercebench.llm.model.ChatMessage;

import java.util.List;

/**
 * 一次 episode 的产出；对齐 Python run() 结束时写回 job 的终止信息与轨迹。
 *
 * <p>{@code messages} 为完整会话（含初始 system/user 与全部 assistant/tool/nudge），{@code terminationReason}
 * 为规范终态， {@code terminationDetail} 保留明细字符串，另有轮数、最终营业日与总资产、上下文裁剪统计。
 */
public record RunResult(
        TerminationReason terminationReason,
        String terminationDetail,
        int turns,
        List<ChatMessage> messages,
        int finalDay,
        String finalDate,
        double finalTotalBalance,
        int contextClearCount,
        int contextTokensFreedTotal) {

    public RunResult {
        messages = List.copyOf(messages);
    }

    /**
     * Python 管线期望的两类规范终态字符串（env_completed / env_terminated）。
     */
    public String canonicalReason() {
        return terminationReason.canonical();
    }
}
