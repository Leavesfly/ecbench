package io.github.ecommercebench.app.log;

import io.github.ecommercebench.domain.money.Money;
import java.time.LocalDate;

/**
 * 某一天的余额快照，用于写入与 Python 兼容的 {@code run_{idx}_daily_balance.csv} 一行。
 *
 * <p>字段与 CSV 列一一对应：date、bank_balance、platform_wallet、total_balance、open_stores、
 * warehouse_items、storage_charged。其中 {@code totalBalance} = 银行 + 钱包 + 待结算托管，是评估的权威列。
 */
public record DailyBalance(
    LocalDate date,
    Money bankBalance,
    Money platformWallet,
    Money totalBalance,
    int openStores,
    int warehouseItems,
    Money storageCharged) {}
