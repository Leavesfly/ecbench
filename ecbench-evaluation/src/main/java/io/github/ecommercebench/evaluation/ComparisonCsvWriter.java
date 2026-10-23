package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * 把 {@link ComparisonReport} 渲染为长表（tidy）CSV，供看板导出后做二次分析。
 *
 * <p>一行一个 (会话, 指标) 对，列为 {@code session,metric,mean,std,n}。刻意不含 model 列：
 * {@link SessionComparison} 没有该字段，而「会话名最后一个下划线段即模型键」是 app 层的命名约定
 * （{@code SessionScanner.modelKey}），下沉到本模块属错误分层。{@code mean} 为 null 或 NaN 时
 * mean/std 两格留空而 n 照出，使两列保持纯数值；{@code MetricStat.fallback} 是展示占位，有意不导出。
 *
 * <p>只读 {@link ComparisonReport}，<b>绝不触碰 {@code RunComparator.METRIC_DEFS}</b>——那是与 Python
 * {@code compare_runs} 对齐的冻结契约，加行会改变 CLI 输出表形状并打破兼容性测试。引号转义交给
 * commons-csv，不自己拼字符串。
 */
public final class ComparisonCsvWriter {

    /** 数值格式：比看板 UI 的 4 位更高精度，便于二次分析。 */
    private static final String NUMBER = "%.6f";

    private static final String[] HEADER = {"session", "metric", "mean", "std", "n"};

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader(HEADER).get();

    /** 按会话序 × 该会话 {@code metrics()} 的原始顺序逐行输出；空报告只出表头。 */
    public String toLongCsv(ComparisonReport report) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, FORMAT)) {
            for (SessionComparison session : report.sessions()) {
                for (MetricStat stat : session.metrics()) {
                    printer.print(session.name());
                    printer.print(stat.label());
                    printer.print(number(stat.mean()));
                    printer.print(number(stat.std()));
                    printer.print(stat.n());
                    printer.println();
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法渲染指标矩阵 CSV", exception);
        }
        return out.toString();
    }

    /** null 与 NaN 都渲染为空单元格（而不是 n/a），保证 mean/std 是纯数值列。 */
    private static String number(Double value) {
        if (value == null || Double.isNaN(value)) {
            return "";
        }
        return String.format(NUMBER, value);
    }
}
