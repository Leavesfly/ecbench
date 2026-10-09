package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 工具入参与输出格式化的公共小工具。
 */
final class ToolArgs {

    private ToolArgs() {
    }

    /**
     * 读取可选字符串参数；缺失或 JSON null 返回 Java null。
     */
    static String string(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /**
     * 读取可选字符串参数；缺失时返回给定默认值。
     */
    static String string(JsonNode args, String field, String fallback) {
        String value = string(args, field);
        return value == null ? fallback : value;
    }

    /**
     * 按 Python 切片语义截断标题；null 视作空串。
     */
    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 读取必需字符串参数；缺失或空白时抛 IllegalArgumentException，由管理器转成稳定错误 JSON。
     */
    static String require(JsonNode args, String field) {
        String value = string(args, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "'");
        }
        return value;
    }

    /**
     * 读取可选布尔参数，缺失时返回默认值。
     */
    static boolean bool(JsonNode args, String field, boolean fallback) {
        JsonNode node = args == null ? null : args.get(field);
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    /**
     * 读取可选整数参数，缺失或非数值时返回默认值。
     */
    static int intOr(JsonNode node, int fallback) {
        return node == null || node.isNull() ? fallback : node.asInt(fallback);
    }

    /**
     * 把 JSON 数值/数字字符串解析为 Money；无法解析时返回 fallback（触发下游正数校验）。
     */
    static Money money(JsonNode node, Money fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return new Money(node.decimalValue());
            }
            return Money.of(node.asText().trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 把 JSON 数值/数字字符串解析为 BigDecimal；无法解析时返回 fallback。
     */
    static BigDecimal decimal(JsonNode node, BigDecimal fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            return new BigDecimal(node.asText().trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
