package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionScannerTest {

  private void writeAnalysis(Path sessionDir, int run) throws Exception {
    Path metrics = sessionDir.resolve("metrics");
    Files.createDirectories(metrics);
    Files.writeString(
        metrics.resolve("run_" + run + "_analysis.json"), "{\"reward\":{\"final_score\":1.0}}");
  }

  @Test
  void scansSessionsWithRunCountsAndModelKey(@TempDir Path log) throws Exception {
    Path s1 = log.resolve("20261010_173016_qwen3.7-plus");
    Path s2 = log.resolve("20261010_180000_gpt-5.6-sol");
    writeAnalysis(s1, 0);
    writeAnalysis(s2, 0);
    writeAnalysis(s2, 1);
    Files.createDirectories(s2.resolve("balance"));

    List<SessionInfo> infos = new SessionScanner(log).scan();

    assertThat(infos)
        .extracting(SessionInfo::name)
        .containsExactly("20261010_173016_qwen3.7-plus", "20261010_180000_gpt-5.6-sol");
    assertThat(infos.get(0).modelKey()).isEqualTo("qwen3.7-plus");
    assertThat(infos.get(0).runCount()).isEqualTo(1);
    assertThat(infos.get(0).hasBalance()).isFalse();
    assertThat(infos.get(1).runCount()).isEqualTo(2);
    assertThat(infos.get(1).hasBalance()).isTrue();
  }

  @Test
  void rejectsPathTraversal(@TempDir Path log) {
    SessionScanner scanner = new SessionScanner(log);
    assertThatThrownBy(() -> scanner.sessionDir("../etc"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> scanner.sessionDir("a/b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SessionScanner.isValidName("20261010_qwen3.7-plus")).isTrue();
  }
}
