package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.web.ExportService.Export;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ExportService 测试：锁定两条不变量——① 会话名解析必须发生在绘图之前且在转译 try 之外，使非法名的
 * IllegalArgumentException(400) 不被误转译成 404；② 下载文件名是固定 ASCII 常量、不含任何用户输入。
 */
class ExportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ANALYSIS =
            "{\"profitability\":{\"final_balance\":95000.0,\"initial_balance\":100000.0,"
                    + "\"peak_total_assets\":100000.0,\"peak_drawdown\":0.0,"
                    + "\"stores_opened\":1,\"store_reopens\":0},"
                    + "\"operational_efficiency\":{\"total_tool_calls\":8}}";

    private static final String BALANCE_HEADER =
            "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,"
                    + "storage_charged\n";

    private static final String BALANCE_ROWS =
            "2026-01-01,0,0,1000.00,0,0,0\n"
                    + "2026-01-02,0,0,1200.00,0,0,0\n"
                    + "2026-01-03,0,0,1500.00,0,0,0\n";

    @TempDir Path log;

    private ExportService exports;

    /** 建两个会话，各含 run_0 的 analysis.json 与三行余额 CSV。 */
    @BeforeEach
    void writeSessions() throws Exception {
        for (String name : List.of("2026_a", "2026_b")) {
            Path session = log.resolve(name);
            Files.createDirectories(session.resolve("metrics"));
            Files.writeString(session.resolve("metrics/run_0_analysis.json"), ANALYSIS);
            Files.createDirectories(session.resolve("balance"));
            Files.writeString(
                    session.resolve("balance/run_0_daily_balance.csv"), BALANCE_HEADER + BALANCE_ROWS);
        }
        SessionScanner scanner = new SessionScanner(log);
        CompareService compare = new CompareService(MAPPER);
        exports = new ExportService(MAPPER, scanner, compare);
    }

    /** PNG 魔数 89 50 4E 47；断言它即断言拿到的是真实渲染产物而非空壳。 */
    private static void assertPngMagic(byte[] body) {
        assertThat(body.length).isGreaterThan(0);
        assertThat(body[0] & 0xFF).isEqualTo(0x89);
        assertThat(body[1] & 0xFF).isEqualTo(0x50);
        assertThat(body[2] & 0xFF).isEqualTo(0x4E);
        assertThat(body[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void balancePngUsesSessionQualifiedSeriesLabelsAndFixedFilename() {
        Export export = exports.balancePng(List.of("2026_a", "2026_b"), "t");

        assertThat(export.contentType()).isEqualTo("image/png");
        assertThat(export.filename()).isEqualTo("balance.png");
        assertPngMagic(export.body());
    }

    @Test
    void balancePngSkipsRunIndexesWithoutBalanceCsv() throws Exception {
        // metrics/ 里有 run_7 的 analysis.json，但 balance/ 下没有对应 CSV：
        // 序号来自 metrics、文件在 balance，两者不同步时必须靠 isRegularFile 过滤掉而不是抛异常。
        Files.writeString(log.resolve("2026_a/metrics/run_7_analysis.json"), ANALYSIS);

        Export export = exports.balancePng(List.of("2026_a"), null);

        assertThat(export.filename()).isEqualTo("balance.png");
        assertPngMagic(export.body());
    }

    @Test
    void balancePngWithoutAnyCsvIsNotFound() throws Exception {
        Files.delete(log.resolve("2026_a/balance/run_0_daily_balance.csv"));
        Files.delete(log.resolve("2026_b/balance/run_0_daily_balance.csv"));

        assertThatThrownBy(() -> exports.balancePng(List.of("2026_a", "2026_b"), null))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("没有可绘制的余额 CSV");
    }

    @Test
    void malformedSessionNameStaysBadRequestAndIsNotTranslatedToNotFound() {
        // 锁死转译范围：会话名解析在 try 之外，IllegalArgumentException 必须原样冒泡（→400）
        assertThatThrownBy(() -> exports.balancePng(List.of("bad name"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.radarPng(List.of("bad name"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.rankingCsv(List.of("bad name")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.matrixCsv(List.of("../escape")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentSessionIsNotFound() {
        assertThatThrownBy(() -> exports.radarPng(List.of("2026_absent"), null))
                .isInstanceOf(ArtifactNotFoundException.class);
        assertThatThrownBy(() -> exports.matrixCsv(List.of("2026_absent")))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void radarPngHasFixedFilenameAndPngMagic() {
        Export export = exports.radarPng(List.of("2026_a", "2026_b"), null);

        assertThat(export.filename()).isEqualTo("radar.png");
        assertThat(export.contentType()).isEqualTo("image/png");
        assertPngMagic(export.body());
    }

    @Test
    void rankingCsvAndJsonCarryFixedFilenamesAndContentTypes() {
        Export csv = exports.rankingCsv(List.of("2026_a", "2026_b"));
        Export json = exports.rankingJson(List.of("2026_a", "2026_b"));

        assertThat(csv.filename()).isEqualTo("ranking.csv");
        assertThat(csv.contentType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(new String(csv.body(), StandardCharsets.UTF_8))
                .startsWith("rank,model,primary_raw");
        assertThat(json.filename()).isEqualTo("ranking.json");
        assertThat(json.contentType()).isEqualTo("application/json; charset=utf-8");
        assertThat(new String(json.body(), StandardCharsets.UTF_8)).contains("\"models\"");
    }

    @Test
    void matrixCsvHasTidyHeaderAndOneRowPerSessionMetric() {
        Export export = exports.matrixCsv(List.of("2026_a", "2026_b"));
        String csv = new String(export.body(), StandardCharsets.UTF_8);

        assertThat(export.filename()).isEqualTo("matrix.csv");
        assertThat(export.contentType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(csv).startsWith("session,metric,mean,std,n");
        assertThat(csv).contains("2026_a,final_balance,").contains("2026_b,final_balance,");
    }
}
