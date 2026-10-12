package io.github.ecommercebench.app.web;

/**
 * dashboard 发现的一个会话（一个 {@code <时间戳>_<模型键>} 目录）及其 run 概览。
 *
 * @param name 会话目录名
 * @param modelKey 从目录名推断的模型键（最后一个下划线之后的段，无下划线时回退整个名字）
 * @param runCount 该会话下 {@code metrics/run_<i>_analysis.json} 的个数
 * @param hasBalance 是否存在可绘制余额曲线的产物（{@code balance/} 子目录且有 run）
 */
public record SessionInfo(String name, String modelKey, int runCount, boolean hasBalance) {}
