package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 仿真的三账户聚合：银行账户、平台钱包和待结算托管资金。
 *
 * <p>经营成本从银行账户支付；已发货收入先进入托管，到期后进入钱包；提现再把钱包资金转入银行。
 */
public final class Accounts {

  private Money bank;
  private Money wallet = Money.ZERO;
  private Money commissionReversed = Money.ZERO;
  private final List<EscrowBatch> escrowBatches = new ArrayList<>();
  private int consecutiveNegativeDays;

  public Accounts(Money initialBank) {
    this.bank = Objects.requireNonNull(initialBank, "initialBank 不能为空");
  }

  public Money bank() {
    return bank;
  }

  public Money wallet() {
    return wallet;
  }

  public List<EscrowBatch> escrowBatches() {
    return List.copyOf(escrowBatches);
  }

  public Money pendingSettlement() {
    return escrowBatches.stream().map(EscrowBatch::amount).reduce(Money.ZERO, Money::add);
  }

  public Money totalAssets() {
    return bank.add(wallet).add(pendingSettlement());
  }

  public void chargeBank(Money amount) {
    requireNonNegative(amount);
    bank = bank.subtract(amount);
  }

  public void creditBank(Money amount) {
    requireNonNegative(amount);
    bank = bank.add(amount);
  }

  public void creditWallet(Money amount) {
    requireNonNegative(amount);
    wallet = wallet.add(amount);
  }

  public void addEscrow(EscrowBatch batch) {
    if (batch.amount().isNegative()) {
      throw new IllegalArgumentException("托管金额不能为负数");
    }
    escrowBatches.add(batch);
  }

  public Money settleMatured(LocalDate day) {
    Money settled = Money.ZERO;
    Iterator<EscrowBatch> iterator = escrowBatches.iterator();
    while (iterator.hasNext()) {
      EscrowBatch batch = iterator.next();
      if (!batch.settleDate().isAfter(day)) {
        settled = settled.add(batch.amount());
        iterator.remove();
      }
    }
    wallet = wallet.add(settled);
    return settled;
  }

  /** 退款优先冲抵对应托管批次；托管仅含扣除佣金后的净收入，差额由平台撤销佣金承担。 */
  public void refund(long batchId, Money amount) {
    requireNonNegative(amount);
    Money remaining = amount;
    for (EscrowBatch batch : escrowBatches) {
      if (batch.batchId() == batchId && !remaining.isZero()) {
        Money deducted = batch.deductUpTo(remaining);
        remaining = remaining.subtract(deducted);
        break;
      }
    }
    escrowBatches.removeIf(batch -> batch.amount().isZero());
    if (!remaining.isZero()) {
      commissionReversed = commissionReversed.add(remaining);
    }
  }

  public Money commissionReversed() {
    return commissionReversed;
  }

  public Money withdraw(Money requested) {
    Money amount = requested == null || requested.compareTo(Money.ZERO) <= 0 ? wallet : requested;
    if (amount.compareTo(wallet) > 0) {
      throw new IllegalArgumentException("提现金额超过平台钱包余额");
    }
    if (amount.isZero()) {
      throw new IllegalArgumentException("平台钱包为空");
    }
    wallet = wallet.subtract(amount);
    bank = bank.add(amount);
    return amount;
  }

  public void updateNegativeStreak() {
    consecutiveNegativeDays = bank.isNegative() ? consecutiveNegativeDays + 1 : 0;
  }

  public int consecutiveNegativeDays() {
    return consecutiveNegativeDays;
  }

  private static void requireNonNegative(Money amount) {
    Objects.requireNonNull(amount, "amount 不能为空");
    if (amount.isNegative()) {
      throw new IllegalArgumentException("金额不能为负数");
    }
  }
}
