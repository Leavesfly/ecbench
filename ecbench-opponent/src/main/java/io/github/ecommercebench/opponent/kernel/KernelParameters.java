package io.github.ecommercebench.opponent.kernel;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.SupplierFamily;

/**
 * 单次 supplier/SKU 谈判内核的不可变参数，由 {@code KernelManager#getOrCreateKernel} 依据品类参数、
 * 供应商家族与欺诈类型装配后交给 {@code CounterpartKernel} 使用。
 *
 * <p>价格口径需区分三者：{@code reservationPrice} 既是供应商能接受的最低价又是逐轮还价的下限，
 * {@code maximumPrice} 是报价上限，二者共同界定还价区间；{@code minimumPrice} 只参与
 * 区间跨度（range）与 favourability 的尺度计算，不作限幅边界。
 *
 * @param family                供应商家族，决定行为系数 Preset、让步噪声幅度与固定的策略/情绪提示
 * @param reservationPrice      保留价（有效成本底线）：低于该价的 Agent 报价被判为不利，
 *                              逐轮让步也以它为止跌线
 * @param concessionWillingness 让步意愿 kappa，越大首轮开价越低、逐轮让步越大、越易接受报价
 * @param stance                立场标签（aggressive/neutral/conciliatory），微调开盘价、让步幅度与提示词偏置
 * @param openingHarshness      开盘系数 d0，取值 [0, 0.99]，越大首轮报价越贴近 maximumPrice
 * @param maxRounds             单场谈判的最大回合数，用于推算截止感（deadline）与"过半回合"的离开阈值
 * @param minimumPrice          价格尺度下界：仅参与区间跨度计算，非限幅边界
 * @param maximumPrice          报价上限：开盘价与所有还价最终都限幅到该值以内
 */
public record KernelParameters(
        SupplierFamily family,
        Money reservationPrice,
        double concessionWillingness,
        String stance,
        double openingHarshness,
        int maxRounds,
        Money minimumPrice,
        Money maximumPrice) {
}
