package io.github.ecommercebench.app.log;

import io.github.ecommercebench.agent.tool.ToolExecutionResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 人类可读输出日志写入器，端口 Python {@code _write_output_log}/{@code _log_tool_calls}。
 *
 * <p>打开时写入起始标记 {@code [LOG] output log started}；每条文本自动补行尾换行并 flush。仅记录工具名与响应文本， 不记录任何密钥或授权头。close
 * 幂等。
 */
public final class OutputLogWriter implements AutoCloseable {

    private final BufferedWriter writer;
    private boolean closed;

    public OutputLogWriter(Path file) {
        try {
            this.writer =
                    Files.newBufferedWriter(
                            file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writer.write("[LOG] output log started\n");
            writer.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法打开输出日志: " + file, exception);
        }
    }

    public void write(String text) {
        try {
            writer.write(text);
            if (!text.endsWith("\n")) {
                writer.write("\n");
            }
            writer.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("写入输出日志失败", exception);
        }
    }

    public void logToolResults(List<ToolExecutionResult> results) {
        for (ToolExecutionResult result : results) {
            write("[TOOL_RESP] " + result.toolName() + " resp=" + result.content());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("关闭输出日志失败", exception);
        }
    }
}
