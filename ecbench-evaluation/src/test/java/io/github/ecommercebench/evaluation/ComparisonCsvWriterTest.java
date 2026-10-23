package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ComparisonCsvWriter 测试：长表（tidy）形状、缺失均值的空格处理、含逗号标签的引号转义。
 */
class ComparisonCsvWriterTest {

    private static ComparisonReport report() {
        SessionComparison a =
                new SessionComparison(
                        "2026_a",
                        2,
                        List.of(
                                new MetricStat("final_balance", 95000.0, 1200.5, 2, "-"),
                                new MetricStat("bankrupt", null, null, 0, "n/a")));
        SessionComparison b =
                new SessionComparison(
                        "2026_b", 1, List.of(new MetricStat("SE+, strong", 0.25, null, 1, "-")));
        return new ComparisonReport(List.of(a, b));
    }

    /** 按行切分并丢掉尾部空行，便于逐行断言（commons-csv 以 CRLF 结尾）。 */
    private static List<String> lines(String csv) {
        return Arrays.stream(csv.split("\r\n|\n|\r")).filter(line -> !line.isEmpty()).toList();
    }

    @Test
    void writesTidyRowsInSessionThenMetricOrder() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(0)).isEqualTo("session,metric,mean,std,n");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1)).isEqualTo("2026_a,final_balance,95000.000000,1200.500000,2");
        assertThat(rows.get(3)).startsWith("2026_b,");
    }

    @Test
    void leavesMeanAndStdEmptyWhenMeanMissingButKeepsN() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(2)).isEqualTo("2026_a,bankrupt,,,0");
    }

    @Test
    void quotesLabelsContainingCommas() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(3)).isEqualTo("2026_b,\"SE+, strong\",0.250000,,1");
    }

    @Test
    void emptyReportYieldsHeaderOnly() {
        String csv = new ComparisonCsvWriter().toLongCsv(new ComparisonReport(List.of()));

        assertThat(lines(csv)).containsExactly("session,metric,mean,std,n");
    }
}
