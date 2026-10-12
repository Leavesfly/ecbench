package io.github.ecommercebench.evaluation.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐谈判原始记录 JSONL 读取器（论文 §E.6 学习维度指标的唯一输入）。
 *
 * <p>文件不存在时返回空列表（旧产物没有该文件，属正常情况而非错误）；空行、非法 JSON 行与非对象行**跳过而不中断**，与
 * {@code ChatboxExtractor} 对半写入行的处理一致。缺失字段一律容忍为 null / 空列表，数组内类型不符或为 JSON null 的元素
 * 同样被跳过（不得填 null，否则 {@code List.copyOf} 会抛 NPE 而废掉整份产物），由下游过滤器决定是否剔除。
 */
public final class NegotiationRecordJsonlReader {

    private final ObjectMapper mapper;

    public NegotiationRecordJsonlReader() {
        this(new ObjectMapper());
    }

    public NegotiationRecordJsonlReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取全部可用行；文件缺失返回空列表。 */
    public List<NegotiationRecordRow> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<NegotiationRecordRow> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node = parse(trimmed);
                if (node == null || !node.isObject()) {
                    continue;
                }
                rows.add(toRow(node));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取逐谈判记录: " + file, exception);
        }
        return rows;
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (IOException exception) {
            return null;
        }
    }

    private NegotiationRecordRow toRow(JsonNode node) {
        return new NegotiationRecordRow(
                text(node, "supplier"),
                text(node, "sku_id"),
                text(node, "supplier_type"),
                text(node, "supplier_family"),
                number(node, "reference_price"),
                number(node, "cost_floor"),
                number(node, "initial_offer"),
                number(node, "final_price"),
                text(node, "outcome"),
                text(node, "terminated_by"),
                integer(node, "day_started"),
                integer(node, "day_concluded"),
                integer(node, "rounds"),
                texts(node, "critical_violations"),
                numbers(node, "agent_prices"),
                numbers(node, "supplier_prices"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static List<String> texts(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(value.size());
        value.forEach(
                item -> {
                    if (!item.isNull()) {
                        out.add(item.asText());
                    }
                });
        return out;
    }

    private static List<Double> numbers(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(value.size());
        value.forEach(
                item -> {
                    if (item.isNumber()) {
                        out.add(item.asDouble());
                    }
                });
        return out;
    }
}
