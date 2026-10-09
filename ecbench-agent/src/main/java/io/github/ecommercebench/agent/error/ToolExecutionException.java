package io.github.ecommercebench.agent.error;

/**
 * 工具基础设施发生不可恢复错误时抛出；由工具管理器归类为批次级严重错误。
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
