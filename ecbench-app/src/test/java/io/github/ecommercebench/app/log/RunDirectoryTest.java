package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunDirectory 路径契约测试：新增的逐谈判记录产物必须落在 metrics/ 下并沿用 run_{idx}_ 前缀。 */
class RunDirectoryTest {

    @Test
    void negotiationRecordsJsonlLivesUnderMetricsDir(@TempDir Path logDir) {
        RunDirectory directory = RunDirectory.create(logDir, "20260101_080000", "fake/model");

        // 用字面量钉住产物位置（spec §4.1 的硬契约），避免断言复制实现表达式而失去意义
        assertThat(directory.negotiationRecordsJsonl(0))
                .isEqualTo(
                        logDir.resolve(
                                "20260101_080000_fake_model/metrics/run_0_negotiation_records.jsonl"));
        assertThat(directory.negotiationRecordsJsonl(3).getFileName().toString())
                .isEqualTo("run_3_negotiation_records.jsonl");
    }

    @Test
    void createUniqueKeepsThePlainNameWhenNothingCollides(@TempDir Path logDir) {
        RunDirectory directory = RunDirectory.createUnique(logDir, "20260101_080000", "gpt");

        assertThat(directory.sessionDir()).isEqualTo(logDir.resolve("20260101_080000_gpt"));
    }

    @Test
    void createUniqueAppendsSuffixWhenTheSessionDirectoryAlreadyExists(@TempDir Path logDir) {
        RunDirectory.createUnique(logDir, "20260101_080000", "gpt");

        RunDirectory second = RunDirectory.createUnique(logDir, "20260101_080000", "gpt");
        RunDirectory third = RunDirectory.createUnique(logDir, "20260101_080000", "gpt");

        assertThat(second.sessionDir()).isEqualTo(logDir.resolve("20260101_080000_gpt-2"));
        assertThat(third.sessionDir()).isEqualTo(logDir.resolve("20260101_080000_gpt-3"));
        assertThat(Files.isDirectory(third.metricsDir())).isTrue();
    }

    @Test
    void suffixedNameStaysWithinTheSessionScannerCharset(@TempDir Path logDir) {
        RunDirectory.createUnique(logDir, "20260101_080000", "gpt");

        String name = RunDirectory.createUnique(logDir, "20260101_080000", "gpt")
                .sessionDir()
                .getFileName()
                .toString();

        assertThat(name).matches("[A-Za-z0-9._-]+");
    }
}
