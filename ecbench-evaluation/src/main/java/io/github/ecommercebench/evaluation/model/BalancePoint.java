package io.github.ecommercebench.evaluation.model;

/**
 * 余额 CSV 的一行（一个交易日的快照）。
 *
 * <p>{@code totalBalance} 是评估与绘图的权威列（= bank + wallet + escrow），读取时优先取 CSV 的 total_balance 列原值，
 * 仅当该列缺失/不可解析时才回退到 bank+wallet，以免静默丢弃托管资金。date 保留原始字符串以兼容不同来源格式。
 */
public record BalancePoint(
    String date,
    double bankBalance,
    double platformWallet,
    double totalBalance,
    int openStores,
    int warehouseItems,
    double storageCharged) {}
