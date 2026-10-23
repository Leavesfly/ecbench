package io.github.ecommercebench.app.web;

/**
 * 携带 HTTP 状态码的 API 异常，用于既有映射覆盖不到的 403 / 405 / 413。
 *
 * <p>{@code ApiRouter.handle} 的 catch 链中本类<b>必须排在 {@code RuntimeException} 之前</b>，否则会被兜底分支转成
 * 500。400 仍走 {@code IllegalArgumentException}、404 仍走 {@code ArtifactNotFoundException}，不迁移既有约定。
 */
public final class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** 应回的 HTTP 状态码。 */
    public int status() {
        return status;
    }
}
