package io.github.ecommercebench.llm.model;

/**
 * 统一聊天消息角色。
 *
 * <p>枚举常量名供内部类型判断，{@link #wireName()} 则是发往 Provider 请求体与运行产物 JSON 里的
 * role 字面量，也与 job 文件中 role 字段共用同一套词表（未知值回退 USER）。
 * 不同协议对 TOOL 的承载方式不同：OpenAI 类端点直接透传，
 * Anthropic 则需改写为 user 消息内的 tool_result 块。
 */
public enum ChatRole {
    /** 系统提示词；部分协议（如 Anthropic）需提升为顶层字段而非消息项。 */
    SYSTEM("system"),
    /** 用户侧输入：任务描述、仿真上下文与工具交付的提示信息。 */
    USER("user"),
    /** 模型输出，可能同时携带正文与工具调用。 */
    ASSISTANT("assistant"),
    /** 工具执行结果，需靠 toolCallId 与对应的调用关联。 */
    TOOL("tool");

    /** 协议线与运行产物中使用的 role 字面量。 */
    private final String wireName;

    /** 绑定该角色唯一的对外字面量。 */
    ChatRole(String wireName) {
        this.wireName = wireName;
    }

    /** 返回发往 Provider 请求体或写入运行产物 JSON 时使用的 role 字面量。 */
    public String wireName() {
        return wireName;
    }
}
