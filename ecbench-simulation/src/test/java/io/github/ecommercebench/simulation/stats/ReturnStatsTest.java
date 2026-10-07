package io.github.ecommercebench.simulation.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** ReturnStats 期望退货分解累加测试：units_shipped 与各 exp_returns_* 按件累加。 */
class ReturnStatsTest {

  @Test
  void accumulatesShippedUnitsAndExpectedReturnChannels() {
    ReturnStats stats = new ReturnStats();
    // 第一笔：10 件，total=2.0、natural=1.0、price=0.5、shipSpeed=-0.2、defective=0.7
    stats.recordExpected(10, 2.0, 1.0, 0.5, -0.2, 0.7);
    // 第二笔：5 件，total=1.0、natural=0.5、price=0.25、shipSpeed=0.1、defective=0.1
    stats.recordExpected(5, 1.0, 0.5, 0.25, 0.1, 0.1);

    assertThat(stats.unitsShipped()).isEqualTo(15);
    assertThat(stats.expReturnsTotal()).isCloseTo(3.0, within(1e-9));
    assertThat(stats.expReturnsNatural()).isCloseTo(1.5, within(1e-9));
    assertThat(stats.expReturnsPrice()).isCloseTo(0.75, within(1e-9));
    assertThat(stats.expReturnsShipSpeed()).isCloseTo(-0.1, within(1e-9));
    assertThat(stats.expReturnsDefective()).isCloseTo(0.8, within(1e-9));
  }

  @Test
  void expectedChannelsAreIndependentOfActualReturns() {
    ReturnStats stats = new ReturnStats();
    stats.recordExpected(8, 1.6, 0.8, 0.4, 0.2, 0.2);

    // 期望分解不影响实际退货计数
    assertThat(stats.unitsReturned()).isZero();
    assertThat(stats.unitsShipped()).isEqualTo(8);
  }
}
