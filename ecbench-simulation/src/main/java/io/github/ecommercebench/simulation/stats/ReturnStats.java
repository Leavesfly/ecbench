package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.domain.money.Money;

/** 实际退货及其自然、定价、配送、缺陷原因分解。 */
public final class ReturnStats {

  private int unitsReturned;
  private int natural;
  private int price;
  private int shipSpeed;
  private int defective;
  private Money refundLossTotal = Money.ZERO;
  private Money shippingLossOnReturns = Money.ZERO;
  private int unitsShipped;
  private double expReturnsTotal;
  private double expReturnsNatural;
  private double expReturnsPrice;
  private double expReturnsShipSpeed;
  private double expReturnsDefective;

  public void recordActual(
      int units,
      int naturalUnits,
      int priceUnits,
      int shipSpeedUnits,
      int defectiveUnits,
      Money refundLoss,
      Money shippingLoss) {
    unitsReturned += units;
    natural += naturalUnits;
    price += priceUnits;
    shipSpeed += shipSpeedUnits;
    defective += defectiveUnits;
    refundLossTotal = refundLossTotal.add(refundLoss);
    shippingLossOnReturns = shippingLossOnReturns.add(shippingLoss);
  }

  public int unitsReturned() {
    return unitsReturned;
  }

  public int natural() {
    return natural;
  }

  public int price() {
    return price;
  }

  public int shipSpeed() {
    return shipSpeed;
  }

  public int defective() {
    return defective;
  }

  public Money refundLossTotal() {
    return refundLossTotal;
  }

  public Money shippingLossOnReturns() {
    return shippingLossOnReturns;
  }

  /**
   * 发货时记录期望退货分解（端口 Python return_stats 的 units_shipped/exp_returns_*）。
   *
   * <p>各期望值以“件”为单位累加（qty × 对应层的期望退货率），分析时再除以 units_shipped 得到率。 natural=自然基线，defective=质量 downgrade
   * 欺诈泄漏，price=定价管理，shipSpeed=配送速度管理。
   */
  public void recordExpected(
      int quantity,
      double expTotal,
      double expNatural,
      double expPrice,
      double expShipSpeed,
      double expDefective) {
    unitsShipped += quantity;
    expReturnsTotal += expTotal;
    expReturnsNatural += expNatural;
    expReturnsPrice += expPrice;
    expReturnsShipSpeed += expShipSpeed;
    expReturnsDefective += expDefective;
  }

  public int unitsShipped() {
    return unitsShipped;
  }

  public double expReturnsTotal() {
    return expReturnsTotal;
  }

  public double expReturnsNatural() {
    return expReturnsNatural;
  }

  public double expReturnsPrice() {
    return expReturnsPrice;
  }

  public double expReturnsShipSpeed() {
    return expReturnsShipSpeed;
  }

  public double expReturnsDefective() {
    return expReturnsDefective;
  }
}
