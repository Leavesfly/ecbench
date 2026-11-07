package io.github.ecommercebench.opponent.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个供应商的会话、成交计数与破产状态。
 *
 * <p>由 {@code ConversationStore} 按 supplierId 惰性创建，生命周期局限于当前 run；
 * 消息按追加顺序全量保存，裁剪发生在读取端（{@link #history}），不在写入时丢弃。
 */
public final class SupplierConversation {

    /**
     * 全部会话消息，按发生顺序追加；读取方自行按条数窗口截取。
     */
    private final List<Message> messages = new ArrayList<>();
    /**
     * 与该供应商的累计成交次数。
     */
    private int orderCount;
    /**
     * 破产标记；一旦置位就不再回退。
     */
    private boolean bankrupt;

    /**
     * 追加一条会话消息，不做去重也不做长度限制。
     *
     * @param role      消息方向，调用方使用 {@code user}（Agent 发出）或 {@code assistant}（供应商回复）
     * @param content   消息正文
     * @param timestamp 发生时间，用于后续回放与审计
     */
    public void append(String role, String content, Instant timestamp) {
        messages.add(new Message(role, content, timestamp));
    }

    /**
     * 返回最近 limit 条消息（时间正序），不暴露内部列表。
     *
     * @param limit 希望获取的最大条数；≤ 0 时返回空列表，超过总条数时返回全部
     * @return 不可变的消息快照副本
     */
    public List<Message> history(int limit) {
        int safeLimit = Math.max(0, limit);
        int start = Math.max(0, messages.size() - safeLimit);
        return List.copyOf(messages.subList(start, messages.size()));
    }

    /**
     * 累计成交次数加一；chatbox 用它与该供应商的破产阈值比对，达到即标记破产。
     */
    public void recordOrder() {
        orderCount++;
    }

    /**
     * 标记该供应商已破产；无撤销入口，后续会话只能读取历史。
     */
    public void markBankrupt() {
        bankrupt = true;
    }

    public int orderCount() {
        return orderCount;
    }

    public boolean bankrupt() {
        return bankrupt;
    }

    /**
     * 一条供应商会话消息。
     *
     * @param role      消息方向（{@code user} 为 Agent 发出，{@code assistant} 为供应商回复）
     * @param content   消息正文
     * @param timestamp 该消息在仿真中的发生时间
     */
    public record Message(String role, String content, Instant timestamp) {
    }
}
