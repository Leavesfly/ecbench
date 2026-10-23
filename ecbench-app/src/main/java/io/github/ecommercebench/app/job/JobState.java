package io.github.ecommercebench.app.job;

/** 任务生命周期状态。 */
public enum JobState {
    /** 已接受，在串行队列中等待。 */
    QUEUED,
    /** 正在执行。 */
    RUNNING,
    /** 全部 run 正常结束（个别 run 失败仍算 SUCCEEDED，失败详情见 outcomes）。 */
    SUCCEEDED,
    /** 编排层抛出未捕获异常。 */
    FAILED,
    /** 被取消，或服务关闭时中断。 */
    CANCELLED;

    /** 是否为终态：终态之后不允许再迁出。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
