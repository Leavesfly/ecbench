package io.github.ecommercebench.simulation.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class WarehouseInventoryTest {

    @Test
    void consumesOldestLotsFirst() {
        WarehouseInventory warehouse = new WarehouseInventory();
        warehouse.addLot(
                new WarehouseLot("sku", 5, LocalDate.parse("2026-01-01"), Money.of("10"), false));
        warehouse.addLot(
                new WarehouseLot("sku", 5, LocalDate.parse("2026-01-05"), Money.of("12"), true));

        WarehouseConsumption consumed = warehouse.consumeFifo("sku", 7);

        assertThat(consumed.quantity()).isEqualTo(7);
        assertThat(consumed.defectiveQuantity()).isEqualTo(2);
        assertThat(warehouse.quantityOf("sku")).isEqualTo(3);
        assertThat(warehouse.lots("sku"))
                .singleElement()
                .satisfies(lot -> assertThat(lot.inboundDate()).isEqualTo(LocalDate.parse("2026-01-05")));
    }

    @Test
    void consumesOnlyAvailableQuantity() {
        WarehouseInventory warehouse = new WarehouseInventory();
        warehouse.addLot(
                new WarehouseLot("sku", 3, LocalDate.parse("2026-01-01"), Money.of("10"), false));

        assertThat(warehouse.consumeFifo("sku", 5).quantity()).isEqualTo(3);
        assertThat(warehouse.quantityOf("sku")).isZero();
    }

    @Test
    void allocationDoesNotRemovePhysicalLotsUntilSale() {
        WarehouseInventory warehouse = new WarehouseInventory();
        warehouse.addLot(
                new WarehouseLot("sku", 10, LocalDate.parse("2026-01-01"), Money.of("10"), false));

        warehouse.allocate("sku", 6);
        assertThat(warehouse.quantityOf("sku")).isEqualTo(4);
        assertThat(warehouse.physicalQuantityOf("sku")).isEqualTo(10);

        warehouse.consumeAllocated("sku", 3);
        assertThat(warehouse.quantityOf("sku")).isEqualTo(4);
        assertThat(warehouse.physicalQuantityOf("sku")).isEqualTo(7);

        warehouse.releaseAllocation("sku", 2);
        assertThat(warehouse.quantityOf("sku")).isEqualTo(6);
    }

    @Test
    void storageFeeUsesQuantityAndLotAge() {
        WarehouseInventory warehouse = new WarehouseInventory();
        warehouse.addLot(
                new WarehouseLot("sku", 2, LocalDate.parse("2026-01-01"), Money.of("10"), false));

        Money fee =
                warehouse.calculateStorageFee(
                        LocalDate.parse("2026-01-11"),
                        (lot, ageDays) -> Money.of("0.10").multiply(BigDecimal.valueOf(1L + ageDays)));

        assertThat(fee).isEqualTo(Money.of("2.20"));
    }
}
