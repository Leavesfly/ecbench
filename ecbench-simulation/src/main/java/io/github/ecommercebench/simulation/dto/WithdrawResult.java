package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/** 平台钱包提现结果。 */
public record WithdrawResult(
    boolean success, Money withdrawn, Money bankBalance, Money platformWallet, String error) {

  public static WithdrawResult failure(String error, Money bank, Money wallet) {
    return new WithdrawResult(false, Money.ZERO, bank, wallet, error);
  }
}
