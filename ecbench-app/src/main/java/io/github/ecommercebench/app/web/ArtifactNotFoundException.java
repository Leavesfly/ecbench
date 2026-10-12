package io.github.ecommercebench.app.web;

/** 请求的 run 产物文件不存在时抛出，由 API 层映射为 HTTP 404。 */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String message) {
        super(message);
    }
}
