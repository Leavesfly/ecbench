package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ecommercebench.app.config.RunOptions;

/**
 * 一次 Web 提交的运行参数，与 {@code CliRunOptions} 共享 {@link RunOptions} 契约。
 *
 * <p>13 个字段中<b>只有 9 个可由 HTTP 提交</b>（model / maxTokens / maxTurns / maxDays / initialBalance /
 * dailyFee / maxTokenCapacity / runs / seed）；4 个路径类字段（tokenizerPath / logDir / jobFile / dataDir）
 * 一律取自服务进程自身的启动配置，<b>不接受 body 输入</b>——否则本端点就成了任意路径写入入口。
 *
 * <p>缺省值与 {@code CliRunOptions} 的 {@code @Option(defaultValue=…)} 逐字相同；{@code seed} 缺省为
 * {@code null} 而非 42，因为 42 由 {@code ConfigurationMerger.DEFAULT_SEED} 唯一持有，两处各写一份会静默分叉。
 */
public record JobRequest(
        String model,
        int maxTokens,
        int maxTurns,
        int maxDays,
        double initialBalance,
        double dailyFee,
        int maxTokenCapacity,
        int runs,
        Long seed,
        String tokenizerPath,
        String logDir,
        String jobFile,
        String dataDir)
        implements RunOptions {

    public static final int DEFAULT_MAX_TOKENS = 16384;
    public static final int DEFAULT_MAX_TURNS = 4000;
    public static final int DEFAULT_MAX_DAYS = 365;
    public static final double DEFAULT_INITIAL_BALANCE = 100000.0;
    public static final double DEFAULT_DAILY_FEE = 50.0;
    public static final int DEFAULT_MAX_TOKEN_CAPACITY = 128000;
    public static final int DEFAULT_RUNS = 1;

    /**
     * 从 POST body 解析请求：校验类型与取值范围，非法输入抛 {@code IllegalArgumentException}。
     *
     * @param body 请求体 JSON
     * @param server 服务进程自身的启动参数，只用于取 4 个路径类字段
     */
    public static JobRequest parse(JsonNode body, RunOptions server) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        String model = trimmedText(body, "model");
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("缺少必填字段 model");
        }
        return new JobRequest(
                model,
                positiveInt(body, "maxTokens", DEFAULT_MAX_TOKENS),
                positiveInt(body, "maxTurns", DEFAULT_MAX_TURNS),
                positiveInt(body, "maxDays", DEFAULT_MAX_DAYS),
                positiveDouble(body, "initialBalance", DEFAULT_INITIAL_BALANCE),
                nonNegativeDouble(body, "dailyFee", DEFAULT_DAILY_FEE),
                positiveInt(body, "maxTokenCapacity", DEFAULT_MAX_TOKEN_CAPACITY),
                positiveInt(body, "runs", DEFAULT_RUNS),
                optionalLong(body, "seed"),
                server.tokenizerPath(),
                server.logDir(),
                server.jobFile(),
                server.dataDir());
    }

    private static String trimmedText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText().trim();
    }

    private static int positiveInt(JsonNode body, String field, int fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        requireIntegral(node, field);
        int value = node.asInt();
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return value;
    }

    private static double positiveDouble(JsonNode body, String field, double fallback) {
        double value = doubleOf(body, field, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正数");
        }
        return value;
    }

    private static double nonNegativeDouble(JsonNode body, String field, double fallback) {
        double value = doubleOf(body, field, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
        return value;
    }

    private static double doubleOf(JsonNode body, String field, double fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
        return node.asDouble();
    }

    private static Long optionalLong(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        requireIntegral(node, field);
        return node.asLong();
    }

    /** 拒绝 1.5 这类小数与字符串数字，避免 asInt 静默截断。 */
    private static void requireIntegral(JsonNode node, String field) {
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
    }
}
