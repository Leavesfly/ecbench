package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 供应商名册读取器测试：按 email 建索引、空 fraud_type 归一为 null、缺文件/缺列降级为空 map 而不抛。 */
class SupplierRosterReaderTest {

    private static final String HEADER =
            "supplier_id,supplier_name,supplier_email,supplier_type,personality,urgency,"
                    + "fraud_type,categories_served,bankruptcy_threshold\n";

    private final SupplierRosterReader reader = new SupplierRosterReader();

    @Test
    void indexesByEmailAndKeepsTypePersonalityFraudType(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0001,Swift Supply,SwiftSupply.sup-0001@wholesale.com,good,Enthusiastic,0.816,,"
                        + "Major Appliances,11\n"
                        + "SUP-0241,Cedar Distribution,cedardistribution.sup-0241@wholesale.com,bad,Tough,0.5,"
                        + "vip_fee,All,7\n");

        Map<String, RosterEntry> roster = reader.readByEmail(csv);

        assertThat(roster).hasSize(2);
        RosterEntry good = roster.get("swiftsupply.sup-0001@wholesale.com");
        assertThat(good.name()).isEqualTo("Swift Supply");
        assertThat(good.type()).isEqualTo("good");
        assertThat(good.personality()).isEqualTo("Enthusiastic");
        assertThat(good.fraudType()).isNull();

        RosterEntry bad = roster.get("cedardistribution.sup-0241@wholesale.com");
        assertThat(bad.type()).isEqualTo("bad");
        assertThat(bad.fraudType()).isEqualTo("vip_fee");
    }

    @Test
    void skipsRowsWithoutEmailAndKeepsTheRest(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0002,No Email,,good,Friendly,0.4,,All,5\n"
                        + "SUP-0003,Oak Link,oaklink.sup-0003@wholesale.com,good,Friendly,0.4,,All,5\n");

        Map<String, RosterEntry> roster = reader.readByEmail(csv);

        assertThat(roster).hasSize(1);
        assertThat(roster).containsKey("oaklink.sup-0003@wholesale.com");
    }

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertThat(reader.readByEmail(dir.resolve("absent.csv"))).isEmpty();
    }

    @Test
    void missingEmailColumnYieldsEmptyMap(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(csv, "supplier_id,supplier_name,supplier_type\nSUP-1,Foo,good\n");

        assertThat(reader.readByEmail(csv)).isEmpty();
    }

    /** 引号未闭合使 commons-csv 在迭代时抛 UncheckedIOException；契约要求不穿透，否则 serve 启动失败。 */
    @Test
    void malformedCsvWithUnterminatedQuoteDoesNotThrow(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0004,\"Unterminated,broken@a.com,good,Friendly,0.4,,All,5\n"
                        + "SUP-0005,Trailing Row,trailing@a.com,good,Friendly,0.4,,All,5\n");

        assertThat(reader.readByEmail(csv)).isEmpty();
    }

    /** 单行列数不足只应跳过该行，不得连带丢弃其后的好行。 */
    @Test
    void shortRowIsSkippedAndFollowingGoodRowsSurvive(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0006,Short Row,short@a.com\n"
                        + "SUP-0007,Good Row,good@a.com,good,Friendly,0.4,,All,5\n");

        Map<String, RosterEntry> roster = reader.readByEmail(csv);

        assertThat(roster).hasSize(1);
        assertThat(roster).containsKey("good@a.com");
        assertThat(roster.get("good@a.com").type()).isEqualTo("good");
    }

    /** 表头存在但无数据行时也算名册不可用，需给出一行提示（不得静默）。 */
    @Test
    void headerOnlyCsvYieldsEmptyMap(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(csv, HEADER);

        assertThat(reader.readByEmail(csv)).isEmpty();
    }
}
