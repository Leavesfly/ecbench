package io.github.ecommercebench.opponent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.FraudType;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SupplierPolicyTest {

    private final SupplierPolicy policy = new SupplierPolicy();

    @Test
    void goodSupplierUsesHonestCostFloor() {
        Money floor =
                policy.computeEffectiveFloor(
                        product(), supplier("good", null), params("0.40", "0.60", "0.70"));

        assertThat(floor).isEqualTo(Money.of("40"));
    }

    @Test
    void preemptiveScamRaisesFloorButRespectsScamCap() {
        Money floor =
                policy.computeEffectiveFloor(
                        product(),
                        supplier("bad", FraudType.VIP_FEE.wireName()),
                        params("0.40", "0.60", "0.55"));

        assertThat(floor).isEqualTo(Money.of("55"));
    }

    @Test
    void capCannotPushScammerBelowHonestFloor() {
        Money floor =
                policy.computeEffectiveFloor(
                        product(),
                        supplier("bad", FraudType.FAKE_URGENCY.wireName()),
                        params("0.72", "0.86", "0.50"));

        assertThat(floor).isEqualTo(Money.of("72"));
    }

    private Product product() {
        return new Product(
                "sku",
                "001",
                "cat",
                "beauty",
                "brand",
                "title",
                "Small",
                new BigDecimal("100"),
                new BigDecimal("0.10"));
    }

    private Supplier supplier(String type, String fraud) {
        return new Supplier(
                "SUP-1", "Supplier", "s@example.com", type, "Friendly", 0.5, fraud, List.of("cat"), 10);
    }

    private CategoryParams params(String floor, String wholesale, String cap) {
        return new CategoryParams(
                "cat",
                "beauty",
                "Small",
                BigDecimal.ONE,
                new BigDecimal("200"),
                10,
                20,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                "",
                "linear",
                BigDecimal.ONE,
                new BigDecimal(wholesale),
                new BigDecimal(floor),
                new BigDecimal(cap));
    }
}
