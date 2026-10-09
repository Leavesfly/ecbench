package io.github.ecommercebench.evaluation.model;

import java.util.List;

/**
 * 跨模型归一化画像（论文 §3.7 / Figure 2）。
 *
 * <p>每个模型持有按轴下标对齐的原始值与 min-max 归一化值（缺样本记为 {@link Double#NaN}），另有每轴归一化值的中位数多边形。 轴顺序与 {@code
 * ProfileAxis.values()} 一致。
 */
public record NormalizedProfile(List<ModelProfile> models, List<Double> medianNormalized) {

    public NormalizedProfile {
        models = List.copyOf(models);
        medianNormalized = List.copyOf(medianNormalized);
    }

    /**
     * 单个模型的画像：名称 + 每轴原始值 + 每轴归一化值（缺失为 NaN）。
     */
    public record ModelProfile(String name, List<Double> rawValues, List<Double> normalizedValues) {
        public ModelProfile {
            rawValues = List.copyOf(rawValues);
            normalizedValues = List.copyOf(normalizedValues);
        }
    }
}
