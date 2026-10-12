package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话级论文 §E.6 学习指标聚合器。
 *
 * <p>发现某会话 metrics 目录下全部 {@code run_{i}_negotiation_records.jsonl}（legacy 平铺布局经
 * {@link RunLayoutResolver} 同样命中），按 run 序号升序逐个交给 {@link LearningMetricsCalculator}，再按论文口径聚合：
 * {@code anchorRatio} 取各 episode 比率的均值（**不是**汇总均值之比），{@code zAnchor} 用 Stouffer 法则
 * {@code (1/√k) Σ z_r} 合并，{@code k}（即 {@code episodes}）为「有重复 pair 且零分布非退化」的 run 数，其余指标取各
 * episode 的均值（null 跳过、不计入分母），计数字段求和。
 *
 * <p>会话完全没有该产物时返回 null（而非全 null 的 {@link SessionLearning}），以便调用方区分「旧产物」与「新产物但无重复
 * 采购」两种情形。
 */
public final class LearningMetricsService {

    private static final Pattern RECORDS_FILE = Pattern.compile("run_(\\d+)_negotiation_records\\.jsonl");

    private final NegotiationRecordJsonlReader reader;
    private final LearningMetricsCalculator calculator;
    private final RunLayoutResolver resolver;

    public LearningMetricsService() {
        this(new NegotiationRecordJsonlReader(), new LearningMetricsCalculator(), new RunLayoutResolver());
    }

    public LearningMetricsService(
            NegotiationRecordJsonlReader reader,
            LearningMetricsCalculator calculator,
            RunLayoutResolver resolver) {
        this.reader = reader;
        this.calculator = calculator;
        this.resolver = resolver;
    }

    /** 单个 run 的学习指标；该 run 无逐谈判记录文件时返回 null。 */
    public LearningMetrics forRun(Path sessionDir, int runIndex) {
        Path file = resolver.resolve(sessionDir).negotiationRecordsJsonl(runIndex);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return calculator.calculate(reader.read(file));
    }

    /** 会话级聚合；该会话没有任何逐谈判记录文件时返回 null。 */
    public SessionLearning forSession(Path sessionDir) {
        List<Path> files = recordFiles(sessionDir);
        if (files.isEmpty()) {
            return null;
        }
        List<LearningMetrics> runs = new ArrayList<>(files.size());
        for (Path file : files) {
            List<NegotiationRecordRow> rows = reader.read(file);
            runs.add(calculator.calculate(rows));
        }
        return aggregate(runs);
    }

    /** 按 run 序号升序列出该会话的逐谈判记录文件（organized 与 legacy 布局都覆盖）。 */
    private List<Path> recordFiles(Path sessionDir) {
        Path dir = resolver.resolve(sessionDir).metricsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> RECORDS_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(path -> runIndexOf(path.getFileName().toString())))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出逐谈判记录: " + dir, exception);
        }
    }

    private static int runIndexOf(String fileName) {
        Matcher matcher = RECORDS_FILE.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 论文口径的跨 episode 聚合；全 null 时各均值仍为 null，计数为求和值。 */
    private static SessionLearning aggregate(List<LearningMetrics> runs) {
        int episodes = 0;
        double zSum = 0.0;
        int repeatPairs = 0;
        int reorders = 0;
        for (LearningMetrics run : runs) {
            repeatPairs += run.repeatPairs();
            reorders += run.reorders();
            if (run.zEpisode() != null) {
                episodes++;
                zSum += run.zEpisode();
            }
        }
        return new SessionLearning(
                meanOf(runs, LearningMetrics::anchorRatio),
                meanOf(runs, LearningMetrics::anchorRegret),
                meanOf(runs, LearningMetrics::newLow),
                meanOf(runs, LearningMetrics::newLowRatioZ),
                episodes == 0 ? null : zSum / Math.sqrt(episodes),
                meanOf(runs, LearningMetrics::seHalfLift),
                meanOf(runs, LearningMetrics::seTrend),
                meanOf(runs, LearningMetrics::fraudHalfLift),
                episodes,
                repeatPairs,
                reorders);
    }

    /** 对非 null 值取均值；全为 null 时返回 null（与 RunComparator 跳过 null 的口径一致）。 */
    private static Double meanOf(List<LearningMetrics> runs, Function<LearningMetrics, Double> getter) {
        double sum = 0.0;
        int count = 0;
        for (LearningMetrics run : runs) {
            Double value = getter.apply(run);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }
}
