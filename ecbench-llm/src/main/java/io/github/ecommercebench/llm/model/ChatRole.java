package io.github.ecommercebench.llm.model;

/**
 * 统一聊天消息角色。
 */
public enum ChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    ChatRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
