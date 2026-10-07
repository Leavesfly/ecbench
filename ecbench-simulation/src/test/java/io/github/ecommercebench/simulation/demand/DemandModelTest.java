package io.github.ecommercebench.simulation.demand;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DemandModelTest {

  private final DemandModel model = new DemandModel();

  @Test
  void computesAllFourElasticityFunctions() {
    assertThat(model.priceFactor("linear", 150, 100, 2, 1)).isZero();
    assertThat(model.priceFactor("exponential", 100, 100, 2, 1)).isEqualTo(1.0);
    assertThat(model.priceFactor("constant_elasticity", 200, 100, 2, 1)).isEqualTo(0.25);
    assertThat(model.priceFactor("quadratic", 150, 100, 2, 1)).isEqualTo(0.5);
  }

  @Test
  void calculatesPromotionBoostFromDiscount() {
    DemandModel.PromotionBoost boost =
        model.promotionBoost(new BigDecimal("3.0"), new BigDecimal("2.0"), new BigDecimal("0.15"));

    assertThat(boost.demandMultiplier()).isEqualTo(2.0);
    assertThat(boost.elasticityBoost()).isEqualTo(2.0);
  }

  @Test
  void marketSaturationApproachesCapacity() {
    double factor = model.marketSaturationFactor(50.0, 1000.0);

    assertThat(1000.0 * factor).isCloseTo(47.6190476, within(0.000001));
  }

  @Test
  void interpolatesPriceDrivenReturnMultiplier() {
    assertThat(model.returnPriceMultiplier(115, 100)).isCloseTo(1.25, within(0.000001));
    assertThat(model.returnPriceMultiplier(70, 100)).isEqualTo(0.85);
    assertThat(model.returnPriceMultiplier(200, 100)).isEqualTo(2.20);
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
