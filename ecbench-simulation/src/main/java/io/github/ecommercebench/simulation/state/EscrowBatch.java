package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一笔已发货、尚未到结算日的净销售收入。
 */
public final class EscrowBatch {

    private final long batchId;
    private Money amount;
    private final LocalDate settleDate;
    private final String storeId;

    public EscrowBatch(long batchId, Money amount, LocalDate settleDate, String storeId) {
        this.batchId = batchId;
        this.amount = Objects.requireNonNull(amount, "amount 不能为空");
        this.settleDate = Objects.requireNonNull(settleDate, "settleDate 不能为空");
        this.storeId = Objects.requireNonNull(storeId, "storeId 不能为空");
    }

    public long batchId() {
        return batchId;
    }

    public Money amount() {
        return amount;
    }

    public LocalDate settleDate() {
        return settleDate;
    }

    public String storeId() {
        return storeId;
    }

    Money deductUpTo(Money requested) {
        Money deduction = amount.compareTo(requested) <= 0 ? amount : requested;
        amount = amount.subtract(deduction);
        return deduction;
    }
}
