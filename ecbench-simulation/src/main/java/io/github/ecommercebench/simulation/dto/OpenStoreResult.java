package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.util.List;

/**
 * 开店操作结果：一次 open_store 调用的成功/失败快照。
 *
 * <p>成功时店铺信息、扣费与可选类目均有意义；失败（见 {@link #failure}）时只有 error 与未扣款的
 * bankBalance 可读，storeId/storeType/storeName 为 null，金额为零、类目为空列表。
 *
 * @param success           是否成功开店
 * @param storeId           新店铺 ID，失败时为 null
 * @param storeType         店铺类型（store_types 表中的键）
 * @param storeName         Agent 指定的店铺名称
 * @param setupFeeCharged   本次实际从银行扣除的开店费，失败时为 0
 * @param dailyOpsCost      展示用的每日运营成本，取自店铺类型的 daily_rent；不等于引擎每日实际扣费口径
 *                          （后者为 EconomicRules.operationsCost(tier)，部分 tier 与 daily_rent 不等）
 * @param reopen            是否为重开店（同类型此前开过），重开需重新积累声誉
 * @param allowedCategories 该店铺类型允许经营的商品类目，失败时为空列表
 * @param bankBalance       扣开店费后的银行余额；失败时为未扣款的当前余额
 * @param error             失败原因，成功时为 null
 */
public record OpenStoreResult(
        boolean success,
        String storeId,
        String storeType,
        String storeName,
        Money setupFeeCharged,
        Money dailyOpsCost,
        boolean reopen,
        List<String> allowedCategories,
        Money bankBalance,
        String error) {

    /**
     * 紧凑构造器：把 null 类目列表归一为空列表，调用方无需判空。
     */
    public OpenStoreResult {
        allowedCategories = allowedCategories == null ? List.of() : List.copyOf(allowedCategories);
    }

    /**
     * 构造失败结果：店铺信息置 null、金额置零、类目置空，仅回填错误原因与当前银行余额（未扣款）。
     */
    public static OpenStoreResult failure(String error, Money bankBalance) {
        return new OpenStoreResult(
                false, null, null, null, Money.ZERO, Money.ZERO, false, List.of(), bankBalance, error);
    }
}
