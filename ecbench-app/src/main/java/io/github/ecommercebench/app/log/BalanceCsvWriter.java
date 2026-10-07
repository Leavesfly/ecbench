package io.github.ecommercebench.app.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 每日余额 CSV 写入器，端口 Python {@code _write_balance_row}/{@code _init_local_logging}。
 *
 * <p>表头严格为
 * date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged；
 * 每个日期只写一行（按 date 去重）；金额以 BigDecimal 的 plain string 输出，绝不使用科学记数。每行 flush，close 幂等。
 */
public final class BalanceCsvWriter implements AutoCloseable {

  private static final String HEADER =
      "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged";

  private final BufferedWriter writer;
  private final Set<LocalDate> seenDates = new HashSet<>();
  private boolean closed;

  public BalanceCsvWriter(Path file) {
    try {
      this.writer =
          Files.newBufferedWriter(
              file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      writer.write(HEADER);
      writer.write("\n");
      writer.flush();
    } catch (IOException exception) {
      throw new UncheckedIOException("无法打开余额日志: " + file, exception);
    }
  }

  public void writeRow(DailyBalance balance) {
    if (balance.date() == null || !seenDates.add(balance.date())) {
      return;
    }
    String line =
        String.join(
            ",",
            balance.date().toString(),
            balance.bankBalance().amount().toPlainString(),
            balance.platformWallet().amount().toPlainString(),
            balance.totalBalance().amount().toPlainString(),
            Integer.toString(balance.openStores()),
            Integer.toString(balance.warehouseItems()),
            balance.storageCharged().amount().toPlainString());
    try {
      writer.write(line);
      writer.write("\n");
      writer.flush();
    } catch (IOException exception) {
      throw new UncheckedIOException("写入余额行失败", exception);
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      writer.close();
    } catch (IOException exception) {
      throw new UncheckedIOException("关闭余额日志失败", exception);
    }
  }
}
