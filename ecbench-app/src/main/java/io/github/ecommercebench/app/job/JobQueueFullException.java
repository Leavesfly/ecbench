package io.github.ecommercebench.app.job;

/**
 * 未结束任务数已达上限时抛出。
 *
 * <p>刻意不用 HTTP 层的异常类型：{@code app.job} 不依赖 {@code app.web}，状态码映射由路由层完成（本异常 → 409）。
 */
public final class JobQueueFullException extends RuntimeException {

    public JobQueueFullException(String message) {
        super(message);
    }
}
