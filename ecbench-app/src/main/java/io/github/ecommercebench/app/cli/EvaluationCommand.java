package io.github.ecommercebench.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.BalancePlotter;
import io.github.ecommercebench.evaluation.BalancePlotter.PlotOptions;
import io.github.ecommercebench.evaluation.ChatboxExtractor;
import io.github.ecommercebench.evaluation.CrossModelProfiler;
import io.github.ecommercebench.evaluation.RadarPlotter;
import io.github.ecommercebench.evaluation.RankingTable;
import io.github.ecommercebench.evaluation.RunComparator;
import io.github.ecommercebench.evaluation.model.ChatboxConversation;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 评估子命令组：{@code evaluate plot | extract-chatbox | compare}，端口 Python {@code evaluation/*.py}
 * 三个脚本的命令行入口。
 *
 * <p>各子命令薄封装已测试的评估服务；成功返回 0，失败打印原因并返回 2（不吞异常）。
 */
@Command(
        name = "evaluate",
        description = "评估子命令（绘图 / chatbox 提取 / 多会话比较）",
        subcommands = {
                EvaluationCommand.Plot.class,
                EvaluationCommand.ExtractChatbox.class,
                EvaluationCommand.Compare.class,
                EvaluationCommand.Profile.class
        })
public final class EvaluationCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Command(name = "plot", description = "绘制余额曲线 PNG（total_balance 权威列）")
    static final class Plot implements Callable<Integer> {

        @Option(names = "--input", required = true, split = ",", description = "余额 CSV 路径（逗号分隔）")
        private List<Path> input;

        @Option(names = "--output", required = true, description = "输出 PNG 路径")
        private Path output;

        @Option(names = "--overlay", description = "叠加多曲线到一张图")
        private boolean overlay;

        @Option(names = "--title", description = "图标题")
        private String title;

        @Override
        public Integer call() {
            try {
                Path written = new BalancePlotter().plot(input, new PlotOptions(output, title, overlay));
                System.out.println("Saved: " + written);
                return 0;
            } catch (RuntimeException exception) {
                System.err.println("plot 失败: " + exception.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "extract-chatbox", description = "按供应商提取 chatbox 会话")
    static final class ExtractChatbox implements Callable<Integer> {

        @Option(names = "--input", required = true, description = "run_*_messages.jsonl 路径")
        private Path input;

        @Option(names = "--output", required = true, description = "输出目录")
        private Path output;

        @Override
        public Integer call() {
            try {
                Map<String, ChatboxConversation> conversations = new ChatboxExtractor().extract(input);
                Files.createDirectories(output);
                for (Map.Entry<String, ChatboxConversation> entry : conversations.entrySet()) {
                    String safe = entry.getKey().replace("/", "_").replace("\\", "_");
                    Files.write(
                            output.resolve(safe + ".jsonl"), entry.getValue().rawLines(), StandardCharsets.UTF_8);
                }
                System.out.println("提取 " + conversations.size() + " 个供应商会话到 " + output);
                return 0;
            } catch (RuntimeException | IOException exception) {
                System.err.println("extract-chatbox 失败: " + exception);
                return 2;
            }
        }
    }

    @Command(name = "compare", description = "跨会话比较 analysis 指标")
    static final class Compare implements Callable<Integer> {

        @Parameters(arity = "1..*", description = "会话目录")
        private List<Path> sessions;

        @Override
        public Integer call() {
            try {
                ComparisonReport report = new RunComparator().compare(sessions);
                render(report);
                return 0;
            } catch (RuntimeException exception) {
                System.err.println("compare 失败: " + exception.getMessage());
                return 2;
            }
        }

        private void render(ComparisonReport report) {
            List<SessionComparison> comparisons = report.sessions();
            if (comparisons.isEmpty()) {
                System.out.println("未找到会话。");
                return;
            }
            StringBuilder header = new StringBuilder(String.format("%-20s", "metric"));
            for (SessionComparison session : comparisons) {
                header.append(String.format("%-24s", session.name()));
            }
            System.out.println(header);
            for (String label : labels(comparisons.get(0))) {
                StringBuilder row = new StringBuilder(String.format("%-20s", label));
                for (SessionComparison session : comparisons) {
                    row.append(String.format("%-24s", format(session.metric(label))));
                }
                System.out.println(row);
            }
        }

        private static List<String> labels(SessionComparison session) {
            return session.metrics().stream().map(MetricStat::label).toList();
        }

        private static String format(MetricStat stat) {
            if (stat == null || stat.mean() == null) {
                return stat == null ? "-" : stat.fallback();
            }
            return stat.std() != null && stat.std() > 0
                    ? String.format("%.4f ± %.4f", stat.mean(), stat.std())
                    : String.format("%.4f", stat.mean());
        }
    }

    @Command(name = "profile", description = "跨模型七轴归一化雷达图 + 排名表")
    static final class Profile implements Callable<Integer> {

        @Parameters(arity = "1..*", description = "会话目录（每个=一个模型，含 metrics/run_*_analysis.json）")
        private List<Path> sessions;

        @Option(names = "--output", required = true, description = "雷达图 PNG 输出路径")
        private Path output;

        @Option(names = "--ranking-output", description = "排名表导出路径（.json 为 JSON，其余按 CSV）")
        private Path rankingOutput;

        @Option(names = "--title", description = "图标题")
        private String title;

        @Override
        public Integer call() {
            try {
                ComparisonReport report = new RunComparator().compare(sessions);
                NormalizedProfile profile = new CrossModelProfiler().profile(report);
                RankingTable table = new RankingTable();
                Path png = new RadarPlotter().plot(profile, new RadarPlotter.RadarOptions(output, title));
                System.out.println(table.toText(profile));
                System.out.println("Saved radar: " + png);
                if (rankingOutput != null) {
                    writeRanking(table, profile, rankingOutput);
                    System.out.println("Saved ranking: " + rankingOutput);
                }
                return 0;
            } catch (RuntimeException | IOException exception) {
                System.err.println("profile 失败: " + exception.getMessage());
                return 2;
            }
        }

        private static void writeRanking(RankingTable table, NormalizedProfile profile, Path path)
                throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String name = path.getFileName().toString().toLowerCase();
            String content =
                    name.endsWith(".json") ? table.toJson(profile, new ObjectMapper()) : table.toCsv(profile);
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }
}
