package io.github.ecommercebench.app.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.NegotiationRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 逐谈判原始记录 JSONL 写入器（论文 §E.6 学习维度指标的重算输入）。
 *
 * <p>把 {@link NegotiationRecord} 逐行落盘到 {@code RunDirectory#negotiationRecordsJsonl(int)}，字段名与评估层
 * {@code NegotiationRecordJsonlReader} 一一对应。这是 Java 侧附加产物：Python 参考实现不产出该文件，既有产物格式不受影响。
 * {@link #writeAll} 每次整文件覆盖写并自行开闭流，故 {@link #close()} 是幂等 no-op（与 {@code MetricsJsonWriter} 一致）。
 */
public final class NegotiationRecordJsonlWriter implements AutoCloseable {

    private final Path file;
    private final ObjectMapper mapper;

    public NegotiationRecordJsonlWriter(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /** 覆盖写出全部已完成谈判记录；空列表写出空文件（保持产物存在性可判定）。 */
    public void writeAll(List<NegotiationRecord> records) {
        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            for (NegotiationRecord record : records) {
                writer.write(mapper.writeValueAsString(node(record)));
                writer.write("\n");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("写入逐谈判记录失败: " + file, exception);
        }
    }

    private ObjectNode node(NegotiationRecord record) {
        ObjectNode node = mapper.createObjectNode();
        node.put("supplier", record.supplierName());
        node.put("sku_id", record.skuId());
        node.put("supplier_type", record.supplierType());
        node.put(
                "supplier_family",
                record.supplierFamily() == null ? null : record.supplierFamily().displayName());
        putMoney(node, "reference_price", record.referencePrice());
        putMoney(node, "cost_floor", record.costFloor());
        putMoney(node, "initial_offer", record.initialOffer());
        putMoney(node, "final_price", record.finalPrice());
        node.put("outcome", record.outcome());
        node.put("terminated_by", record.terminatedBy());
        putInteger(node, "day_started", record.dayStarted());
        putInteger(node, "day_concluded", record.dayConcluded());
        node.put("rounds", record.rounds());
        node.set("critical_violations", strings(record.criticalViolations()));
        node.set("agent_prices", money(record.agentPrices()));
        node.set("supplier_prices", money(record.supplierPrices()));
        return node;
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ArrayNode money(List<Money> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(value -> array.add(value.amount().doubleValue()));
        return array;
    }

    private static void putMoney(ObjectNode node, String field, Money value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.amount().doubleValue());
        }
    }

    private static void putInteger(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.intValue());
        }
    }

    /** 无长持资源需释放（{@link #writeAll} 自行开闭流），仅满足 AutoCloseable 契约。 */
    @Override
    public void close() {
        // 幂等 no-op。
    }
}
