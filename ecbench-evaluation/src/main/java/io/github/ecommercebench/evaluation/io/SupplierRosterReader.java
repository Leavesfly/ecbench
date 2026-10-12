package io.github.ecommercebench.evaluation.io;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * 供应商名册读取器：只解析 {@code data/suppliers.csv} 一张表，按 email 建索引。
 *
 * <p>供 dashboard 在 chatbox 谈判记录上标注供应商类型（诚信/欺诈）、人格与欺诈类型（论文附录 G 的失败案例阅读方式）。
 * 刻意**不**复用 {@code CsvCatalogLoader.load()}——后者要求六张 CSV 齐全且任一缺失即抛异常，不适合只读看板。
 * 本读取器全程容错：文件缺失、表头缺 {@code supplier_email} 列、单行坏值都只跳过并在 stderr 记一次警告，永不抛异常。
 */
public final class SupplierRosterReader {

    private static final CSVFormat FORMAT =
            CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();

    /**
     * 名册条目。
     *
     * @param email 小写化的供应商邮箱（索引键）
     * @param fraudType 欺诈类型；诚信供应商为 null
     */
    public record RosterEntry(
            String email, String name, String type, String personality, String fraudType) {}

    /** 按 email（小写）索引名册；任何不可用情形都返回已解析部分，可能为空 map。 */
    public Map<String, RosterEntry> readByEmail(Path suppliersCsv) {
        Map<String, RosterEntry> roster = new LinkedHashMap<>();
        if (suppliersCsv == null || !Files.isRegularFile(suppliersCsv)) {
            warn("供应商名册不存在，跳过欺诈标注: " + suppliersCsv);
            return roster;
        }
        try (Reader source = Files.newBufferedReader(suppliersCsv, StandardCharsets.UTF_8);
                CSVParser parser = FORMAT.parse(source)) {
            if (!parser.getHeaderMap().containsKey("supplier_email")) {
                warn("供应商名册缺少 supplier_email 列，跳过欺诈标注: " + suppliersCsv);
                return roster;
            }
            for (CSVRecord record : parser) {
                RosterEntry entry = toEntry(record);
                if (entry != null) {
                    roster.put(entry.email(), entry);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            warn("供应商名册解析中断（已保留 " + roster.size() + " 条）: " + exception.getMessage());
        }
        return roster;
    }

    private static RosterEntry toEntry(CSVRecord record) {
        String email = trimmed(record, "supplier_email");
        if (email.isEmpty()) {
            return null;
        }
        String fraudType = trimmed(record, "fraud_type");
        return new RosterEntry(
                email.toLowerCase(Locale.ROOT),
                trimmed(record, "supplier_name"),
                trimmed(record, "supplier_type"),
                trimmed(record, "personality"),
                fraudType.isEmpty() ? null : fraudType);
    }

    /** 取列值；列不存在时返回空串而非抛异常（commons-csv 对缺失列会抛 IllegalArgumentException）。 */
    private static String trimmed(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column).trim() : "";
    }

    private static void warn(String message) {
        System.err.println("[serve] " + message);
    }
}
