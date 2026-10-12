package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 逐谈判记录读取器测试：字段解析、null 容忍、坏行跳过、文件缺失降级为空列表。 */
class NegotiationRecordJsonlReaderTest {

    private final NegotiationRecordJsonlReader reader = new NegotiationRecordJsonlReader();

    @Test
    void parsesAllFieldsOfAnAgreementRow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "{\"supplier\":\"oak@a.com\",\"sku_id\":\"sku1\",\"supplier_type\":\"good\","
                        + "\"supplier_family\":\"Candid\",\"reference_price\":100.0,\"cost_floor\":50.0,"
                        + "\"initial_offer\":100.0,\"final_price\":90.0,\"outcome\":\"Agreement\","
                        + "\"terminated_by\":\"SupplierAccept\",\"day_started\":3,\"day_concluded\":4,"
                        + "\"rounds\":5,\"critical_violations\":[\"ResViol\"],"
                        + "\"agent_prices\":[60.0,75.0],\"supplier_prices\":[100.0,95.0]}\n");

        List<NegotiationRecordRow> rows = reader.read(file);

        assertThat(rows).hasSize(1);
        NegotiationRecordRow row = rows.get(0);
        assertThat(row.supplier()).isEqualTo("oak@a.com");
        assertThat(row.skuId()).isEqualTo("sku1");
        assertThat(row.supplierType()).isEqualTo("good");
        assertThat(row.supplierFamily()).isEqualTo("Candid");
        assertThat(row.referencePrice()).isEqualTo(100.0);
        assertThat(row.costFloor()).isEqualTo(50.0);
        assertThat(row.initialOffer()).isEqualTo(100.0);
        assertThat(row.finalPrice()).isEqualTo(90.0);
        assertThat(row.outcome()).isEqualTo("Agreement");
        assertThat(row.terminatedBy()).isEqualTo("SupplierAccept");
        assertThat(row.dayStarted()).isEqualTo(3);
        assertThat(row.dayConcluded()).isEqualTo(4);
        assertThat(row.rounds()).isEqualTo(5);
        assertThat(row.criticalViolations()).containsExactly("ResViol");
        assertThat(row.agentPrices()).containsExactly(60.0, 75.0);
        assertThat(row.supplierPrices()).containsExactly(100.0, 95.0);
        assertThat(row.isAgreement()).isTrue();
    }

    @Test
    void toleratesMissingNullableFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "{\"supplier\":\"bad@a.com\",\"sku_id\":\"sku2\",\"supplier_type\":\"bad\","
                        + "\"reference_price\":80.0,\"cost_floor\":40.0,\"outcome\":\"Disagreement\"}\n");

        NegotiationRecordRow row = reader.read(file).get(0);

        assertThat(row.finalPrice()).isNull();
        assertThat(row.dayConcluded()).isNull();
        assertThat(row.dayStarted()).isNull();
        assertThat(row.rounds()).isNull();
        assertThat(row.supplierFamily()).isNull();
        assertThat(row.criticalViolations()).isEmpty();
        assertThat(row.agentPrices()).isEmpty();
        assertThat(row.supplierPrices()).isEmpty();
        assertThat(row.isAgreement()).isFalse();
    }

    @Test
    void skipsBlankAndMalformedLinesWithoutFailing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "\n"
                        + "{不是 JSON\n"
                        + "{\"supplier\":\"ok@a.com\",\"sku_id\":\"s\",\"supplier_type\":\"good\"}\n"
                        + "[1,2,3]\n");

        List<NegotiationRecordRow> rows = reader.read(file);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).supplier()).isEqualTo("ok@a.com");
    }

    @Test
    void missingFileYieldsEmptyList(@TempDir Path dir) {
        assertThat(reader.read(dir.resolve("absent.jsonl"))).isEmpty();
    }
}
