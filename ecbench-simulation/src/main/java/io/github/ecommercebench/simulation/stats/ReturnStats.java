package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.domain.money.Money;

/**
 * 退货统计累加器：同时维护实际退货与期望退货两套口径，二者互不影响。
 *
 * <p>实际口径由 {@link #recordActual} 在退货到库时累加件数与金钱损失，退货率的件数分母取
 * FulfilmentStats.unitsSold；期望口径由 {@link #recordExpected} 在发货时按「件数 × 各层退货率」累加
 * 期望退货件数，除以 {@link #unitsShipped} 即得期望退货率。两套值最终由 MetricsJsonWriter 在
 * return_management 面板里合并输出。
 */
public final class ReturnStats {

    /**
     * 实际退货件数：ReturnProcessor 处理到库退货时累加，实际退货率 = 本值 / FulfilmentStats.unitsSold。
     */
    private int unitsReturned;

    /**
     * 实际退货中归因于商品自然基线的件数。
     */
    private int natural;

    /**
     * 实际退货中归因于定价管理的件数；生产路径（ReturnProcessor）只回填自然与缺陷两档，此处暂不累加。
     */
    private int price;

    /**
     * 实际退货中归因于配送速度的件数；生产路径（ReturnProcessor）只回填自然与缺陷两档，此处暂不累加。
     */
    private int shipSpeed;

    /**
     * 实际退货中归因于缺陷批次的件数，由退货携带的缺陷占比四舍五入拆出。
     */
    private int defective;

    /**
     * 退货退款金额合计：按退款单价 × 件数全额退款，不含已付运费。
     */
    private Money refundLossTotal = Money.ZERO;

    /**
     * 退货造成的运费损失合计：已付运费不返还，按每件运费 × 退货件数累计。
     */
    private Money shippingLossOnReturns = Money.ZERO;

    /**
     * 累计发货件数：期望口径的分母，与 FulfilmentStats 按订单笔数计数不同，这里是件数。
     */
    private int unitsShipped;

    /**
     * 期望退货件数合计：发货件数 × 有效退货率（已含缺陷/定价修正，再乘配送速度乘子并限幅 0.95）。
     */
    private double expReturnsTotal;

    /**
     * 期望退货中自然基线贡献的件数。
     */
    private double expReturnsNatural;

    /**
     * 期望退货中定价管理增量贡献的件数（定价修正后率 − 缺陷修正后率）× 发货件数。
     */
    private double expReturnsPrice;

    /**
     * 期望退货中配送速度增量贡献的件数（有效退货率 − 定价修正后率）× 发货件数，加速配送时可为负。
     */
    private double expReturnsShipSpeed;

    /**
     * 期望退货中缺陷/欺诈增量贡献的件数（缺陷修正后率 − 自然基线率）× 发货件数，下限为 0。
     */
    private double expReturnsDefective;

    /**
     * 累加一笔实际到达的退货：件数按原因分档，同时累计退款与运费损失。
     *
     * <p>由 ReturnProcessor 在退货到库、退款并重新入仓时调用；生产路径下只回填自然与缺陷两档，
     * 定价与配送两档传 0。
     *
     * @param units          本笔退货件数
     * @param naturalUnits   其中归因于自然基线的件数
     * @param priceUnits     其中归因于定价管理的件数
     * @param shipSpeedUnits 其中归因于配送速度的件数
     * @param defectiveUnits 其中归因于缺陷批次的件数
     * @param refundLoss     本笔退款金额（退款单价 × 件数）
     * @param shippingLoss   本笔退货对应的已付运费损失（不返还）
     */
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

    /**
     * 实际退货件数（到库口径），对应 output metrics 的 units_returned。
     */
    public int unitsReturned() {
        return unitsReturned;
    }

    /**
     * 实际退货中归因于自然基线的件数。
     */
    public int natural() {
        return natural;
    }

    /**
     * 实际退货中归因于定价管理的件数（生产路径暂未回填）。
     */
    public int price() {
        return price;
    }

    /**
     * 实际退货中归因于配送速度的件数（生产路径暂未回填）。
     */
    public int shipSpeed() {
        return shipSpeed;
    }

    /**
     * 实际退货中归因于缺陷批次的件数。
     */
    public int defective() {
        return defective;
    }

    /**
     * 退货退款金额合计，对应 output metrics 的 refund_loss_total。
     */
    public Money refundLossTotal() {
        return refundLossTotal;
    }

    /**
     * 退货产生的运费损失合计，对应 output metrics 的 shipping_loss_on_returns。
     */
    public Money shippingLossOnReturns() {
        return shippingLossOnReturns;
    }

    /**
     * 累加一批发货的期望退货分解（端口 Python return_stats 的 units_shipped/exp_returns_*）。
     *
     * <p>由 SimulationEngine.shipOrders 在每笔发货时调用，各期望值以「件」为单位累加，与实际口径互不影响；
     * 分析时各 expReturns* 除以 unitsShipped 得到率。各层率均已叠加配送速度乘子并限幅 0.95：
     * natural=自然基线，defective=缺陷/欺诈增量，price=定价管理增量，shipSpeed=配送速度增量。
     *
     * @param quantity     本批发货件数，计入 unitsShipped 分母
     * @param expTotal     期望退货总件数（有效退货率 × 件数）
     * @param expNatural   自然基线贡献件数
     * @param expPrice     定价管理增量贡献件数
     * @param expShipSpeed 配送速度增量贡献件数，加速配送时可能为负
     * @param expDefective 缺陷/欺诈增量贡献件数
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

    /**
     * 累计发货件数，各期望退货率的分母。
     */
    public int unitsShipped() {
        return unitsShipped;
    }

    /**
     * 期望退货件数合计，除以 unitsShipped 得 exp_return_rate。
     */
    public double expReturnsTotal() {
        return expReturnsTotal;
    }

    /**
     * 自然基线期望退货件数，对应 exp_return_rate_natural。
     */
    public double expReturnsNatural() {
        return expReturnsNatural;
    }

    /**
     * 定价管理期望退货增量件数，对应 exp_return_rate_from_pricing。
     */
    public double expReturnsPrice() {
        return expReturnsPrice;
    }

    /**
     * 配送速度期望退货增量件数，对应 exp_return_rate_from_ship_speed，可为负。
     */
    public double expReturnsShipSpeed() {
        return expReturnsShipSpeed;
    }

    /**
     * 缺陷/欺诈期望退货增量件数，对应 exp_return_rate_from_defective_fraud。
     */
    public double expReturnsDefective() {
        return expReturnsDefective;
    }
}
