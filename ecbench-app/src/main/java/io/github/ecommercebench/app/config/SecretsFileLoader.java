package io.github.ecommercebench.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取仓库根 {@code .env} 形式的本地密钥文件。
 *
 * <p>设计上<b>永不抛异常</b>：文件缺失、不可读、空文件、畸形行一律降级为「返回已解析部分」，并在 stderr 记一行原因，
 * 使服务在密钥文件不可用时照常启动。兜底必须覆盖 {@link RuntimeException} 而非只 {@link IOException}——本项目已在
 * {@code SupplierRosterReader} 上踩过：第三方解析器把底层错误包成 {@code UncheckedIOException} 抛出，只 catch
 * 受检异常会让本该降级的路径变成启动崩溃。
 */
public final class SecretsFileLoader {

    /**
     * 解析 {@code KEY=VALUE} 行：忽略空行与 {@code #} 注释，去掉值两侧<b>成对</b>的单/双引号。
     *
     * @param file 密钥文件路径，可为 null
     * @return 解析出的键值对（保持文件顺序）；任何异常情况下返回已成功解析的部分，绝不为 null
     */
    public Map<String, String> read(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                putIfWellFormed(result, raw);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println(
                    "[ecbench] 密钥文件不可读，已忽略: " + file + " (" + exception.getClass().getSimpleName() + ")");
        }
        return result;
    }

    /** 跳过空行、注释与不含 {@code =} 或键为空的畸形行；其余按首个 {@code =} 切分。 */
    private static void putIfWellFormed(Map<String, String> target, String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = line.substring(0, equals).trim();
        if (key.isEmpty()) {
            return;
        }
        target.put(key, unquote(line.substring(equals + 1).trim()));
    }

    /** 仅当首尾是同一种引号且长度足够时才剥除，避免把 {@code "mismatched'} 改坏。 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
