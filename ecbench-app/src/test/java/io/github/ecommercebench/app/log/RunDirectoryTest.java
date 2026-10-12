package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunDirectory 路径契约测试：新增的逐谈判记录产物必须落在 metrics/ 下并沿用 run_{idx}_ 前缀。 */
class RunDirectoryTest {

    @Test
    void negotiationRecordsJsonlLivesUnderMetricsDir(@TempDir Path logDir) {
        RunDirectory directory = RunDirectory.create(logDir, "20260101_080000", "fake/model");

        assertThat(directory.negotiationRecordsJsonl(0))
                .isEqualTo(directory.metricsDir().resolve("run_0_negotiation_records.jsonl"));
        assertThat(directory.negotiationRecordsJsonl(3).getFileName().toString())
                .isEqualTo("run_3_negotiation_records.jsonl");
    }
}
