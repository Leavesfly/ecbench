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

        // 用字面量钉住产物位置（spec §4.1 的硬契约），避免断言复制实现表达式而失去意义
        assertThat(directory.negotiationRecordsJsonl(0))
                .isEqualTo(
                        logDir.resolve(
                                "20260101_080000_fake_model/metrics/run_0_negotiation_records.jsonl"));
        assertThat(directory.negotiationRecordsJsonl(3).getFileName().toString())
                .isEqualTo("run_3_negotiation_records.jsonl");
    }
}
