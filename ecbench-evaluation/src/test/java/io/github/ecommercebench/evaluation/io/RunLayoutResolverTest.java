package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunLayoutResolver 测试：逐谈判记录路径在 organized 与 legacy 两种布局下都能定位。 */
class RunLayoutResolverTest {

    private final RunLayoutResolver resolver = new RunLayoutResolver();

    @Test
    void organizedLayoutResolvesUnderMetricsSubdir(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("metrics"));

        RunLayoutResolver.RunLayout layout = resolver.resolve(session);

        assertThat(layout.organized()).isTrue();
        assertThat(layout.negotiationRecordsJsonl(2))
                .isEqualTo(session.resolve("metrics/run_2_negotiation_records.jsonl"));
    }

    @Test
    void legacyFlatLayoutResolvesUnderSessionRoot(@TempDir Path session) {
        RunLayoutResolver.RunLayout layout = resolver.resolve(session);

        assertThat(layout.organized()).isFalse();
        assertThat(layout.negotiationRecordsJsonl(0))
                .isEqualTo(session.resolve("run_0_negotiation_records.jsonl"));
    }
}
