package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;
import io.github.ecommercebench.opponent.model.NegotiationRecord;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 逐谈判记录写入器测试：字段齐备、空记录写空文件、与评估层读取器 round-trip 一致、close 幂等。 */
class NegotiationRecordJsonlWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NegotiationRecord record(String supplier, String sku, String type) {
        NegotiationRecord record =
                new NegotiationRecord(
                        supplier,
                        sku,
                        type,
                        SupplierFamily.CANDID,
                        money("100.00"),
                        money("50.00"),
                        money("100.00"),
                        3);
        record.recordAgentOffer(money("70.00"));
        record.recordSupplierOffer(money("95.00"));
        record.recordAgentOffer(money("90.00"));
        record.complete("Agreement", money("90.00"), "SupplierAccept", 4);
        return record;
    }

    private static Money money(String amount) {
        return Money.of(amount);
    }

    @Test
    void writesOneLinePerRecordWithAllFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER)
                .writeAll(
                        List.of(
                                record("oak@a.com", "sku1", "good"),
                                record("bad@a.com", "sku2", "bad")));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .contains("\"supplier\":\"oak@a.com\"")
                .contains("\"sku_id\":\"sku1\"")
                .contains("\"supplier_type\":\"good\"")
                .contains("\"supplier_family\":\"Candid\"")
                .contains("\"reference_price\":100.0")
                .contains("\"cost_floor\":50.0")
                .contains("\"initial_offer\":100.0")
                .contains("\"final_price\":90.0")
                .contains("\"outcome\":\"Agreement\"")
                .contains("\"terminated_by\":\"SupplierAccept\"")
                .contains("\"day_started\":3")
                .contains("\"day_concluded\":4")
                .contains("\"rounds\":4")
                .contains("\"critical_violations\":[]")
                .contains("\"agent_prices\":[70.0,90.0]")
                .contains("\"supplier_prices\":[95.0]");
    }

    @Test
    void writesNullForAbsentPriceAndDay(@TempDir Path dir) throws Exception {
        NegotiationRecord open =
                new NegotiationRecord(
                        "x@a.com",
                        "sku",
                        "good",
                        SupplierFamily.CANDID,
                        money("100.00"),
                        money("50.00"),
                        money("100.00"),
                        null);
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of(open));

        assertThat(Files.readString(file))
                .contains("\"final_price\":null")
                .contains("\"day_started\":null")
                .contains("\"day_concluded\":null")
                .contains("\"outcome\":null");
    }

    @Test
    void emptyRecordListProducesEmptyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of());

        assertThat(file).exists();
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void overwritesPreviousContentInsteadOfAppending(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        NegotiationRecordJsonlWriter writer = new NegotiationRecordJsonlWriter(file, MAPPER);

        writer.writeAll(List.of(record("first@a.com", "sku1", "good"), record("second@a.com", "s2", "good")));
        writer.writeAll(List.of(record("only@a.com", "sku1", "good")));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"supplier\":\"only@a.com\"");
    }

    @Test
    void roundTripsThroughEvaluationReader(@TempDir Path dir) {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of(record("oak@a.com", "sku1", "good")));
        List<NegotiationRecordRow> rows = new NegotiationRecordJsonlReader().read(file);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isAgreement()).isTrue();
        assertThat(rows.get(0).supplier()).isEqualTo("oak@a.com");
        assertThat(rows.get(0).referencePrice()).isEqualTo(100.0);
        assertThat(rows.get(0).costFloor()).isEqualTo(50.0);
        assertThat(rows.get(0).finalPrice()).isEqualTo(90.0);
        assertThat(rows.get(0).dayConcluded()).isEqualTo(4);
        assertThat(rows.get(0).agentPrices()).containsExactly(70.0, 90.0);
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) {
        NegotiationRecordJsonlWriter writer =
                new NegotiationRecordJsonlWriter(dir.resolve("run_0_negotiation_records.jsonl"), MAPPER);

        writer.close();
        writer.close();
    }
}
