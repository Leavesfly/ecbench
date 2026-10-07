package io.github.ecommercebench.evaluation.io;

import io.github.ecommercebench.evaluation.model.BalancePoint;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * 余额 CSV 读取器，端口 Python {@code plot_daily_balance._read_first_three_columns}。
 *
 * <p>按表头列名读取；total_balance 为权威列，缺失或空白时回退到 bank+wallet（避免静默丢弃托管资金）。金额以 plain 十进制解析。
 * 坏行抛出的异常信息包含文件名与行号；未知附加列被忽略以支持向前兼容。
 */
public final class BalanceCsvReader {

  public List<BalancePoint> read(Path csv) {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    List<BalancePoint> points = new ArrayList<>();
    try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
        CSVParser parser = format.parse(reader)) {
      Set<String> headers = new HashSet<>(parser.getHeaderNames());
      for (CSVRecord record : parser) {
        long line = record.getRecordNumber() + 1;
        try {
          points.add(parse(record, headers));
        } catch (RuntimeException exception) {
          throw new IllegalArgumentException(
              "余额 CSV 解析失败: " + csv.getFileName() + " 第 " + line + " 行: " + exception.getMessage(),
              exception);
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("无法读取余额 CSV: " + csv, exception);
    }
    return points;
  }

  private BalancePoint parse(CSVRecord record, Set<String> headers) {
    String date = column(record, headers, "date", "");
    double bank = Double.parseDouble(column(record, headers, "bank_balance", "0"));
    double wallet = Double.parseDouble(column(record, headers, "platform_wallet", "0"));
    String totalRaw = column(record, headers, "total_balance", null);
    double total =
        totalRaw == null || totalRaw.isBlank() ? bank + wallet : Double.parseDouble(totalRaw);
    int openStores = (int) Double.parseDouble(column(record, headers, "open_stores", "0"));
    int warehouseItems = (int) Double.parseDouble(column(record, headers, "warehouse_items", "0"));
    double storage = Double.parseDouble(column(record, headers, "storage_charged", "0"));
    return new BalancePoint(date, bank, wallet, total, openStores, warehouseItems, storage);
  }

  private static String column(
      CSVRecord record, Set<String> headers, String name, String fallback) {
    if (!headers.contains(name)) {
      return fallback;
    }
    String value = record.get(name);
    return value == null ? fallback : value.trim();
  }
}
