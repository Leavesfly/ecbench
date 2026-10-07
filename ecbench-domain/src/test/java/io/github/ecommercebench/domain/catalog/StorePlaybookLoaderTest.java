package io.github.ecommercebench.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StorePlaybookLoaderTest {

  @Test
  void loadsRepositoryMarketGuidance() {
    Path json =
        Path.of(System.getProperty("user.dir"), "..", "data", "store_playbook.json").normalize();

    MarketGuidance guidance = new StorePlaybookLoader().load(json);

    assertThat(guidance.profitPotential(1)).isEqualTo("Very high");
    assertThat(guidance.profitPotential(3)).isEqualTo("Modest — low ceiling");
    assertThat(guidance.storeAdvantage("beauty")).contains("PROFIT POTENTIAL: MODEST");
    assertThat(guidance.playbook("beauty").strengths()).isNotEmpty();
    assertThat(guidance.playbook("beauty").challenges()).isNotEmpty();
    assertThat(guidance.playbook("beauty").tips()).isNotEmpty();
    assertThat(guidance.playbook("unknown")).isEqualTo(MarketGuidance.StorePlaybook.EMPTY);
  }
}
