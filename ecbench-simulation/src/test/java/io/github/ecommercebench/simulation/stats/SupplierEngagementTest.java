package io.github.ecommercebench.simulation.stats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SupplierEngagement 测试：按供应商名去重，忽略空白名。
 */
class SupplierEngagementTest {

    @Test
    void dedupesContactedAndOrderedByName() {
        SupplierEngagement engagement = new SupplierEngagement();
        engagement.recordContacted("A");
        engagement.recordContacted("A");
        engagement.recordContacted("B");
        engagement.recordOrdered("A");
        engagement.recordOrdered("A");
        engagement.recordContacted(null);
        engagement.recordContacted("  ");

        assertThat(engagement.contacted()).containsExactly("A", "B");
        assertThat(engagement.ordered()).containsExactly("A");
    }
}
