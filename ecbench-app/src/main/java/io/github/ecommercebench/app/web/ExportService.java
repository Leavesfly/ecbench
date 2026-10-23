package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.BalancePlotter;
import io.github.ecommercebench.evaluation.ComparisonCsvWriter;
import io.github.ecommercebench.evaluation.RadarPlotter;
import io.github.ecommercebench.evaluation.RankingTable;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver.RunLayout;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 看板文件导出的编排：把 evaluation 模块的现成渲染能力包装成可下载的字节。
 *
 * <p>与只读 JSON 端点<b>同源同口径</b>——雷达与排名走 {@link CompareService#compareAll}，余额曲线走
 * {@link BalancePlotter}，指标矩阵走 {@link ComparisonCsvWriter}。本类不自己算任何指标。
 *
 * <p>两条不变量：① 会话名解析必须发生在调用绘图类<b>之前</b>且在其 try 之外，否则非法会话名的
 * {@code IllegalArgumentException}（400）会被误转译成 404；② 下载文件名一律为固定 ASCII 常量，
 * 绝不拼入会话名或 title，杜绝 {@code Content-Disposition} 头注入。
 *
 * <p>余额 PNG 画<b>全部 run</b>：run 序号取自 {@code metrics/}，而余额 CSV 在 {@code balance/} 下，
 * 两者可能不同步，故逐个 {@code isRegularFile} 过滤；图例名带会话前缀，避免多会话的 {@code run_0} 重名。
 */
public final class ExportService {

    /** 一次导出结果：MIME 类型、下载文件名与正文字节。 */
    public record Export(String contentType, String filename, byte[] body) {
    }

    private static final String PNG = "image/png";

    private static final String CSV = "text/csv; charset=utf-8";

    private static final String JSON = "application/json; charset=utf-8";

    private final ObjectMapper mapper;
    private final SessionScanner scanner;
    private final CompareService compare;
    private final RunLayoutResolver resolver;
    private final BalancePlotter balancePlotter = new BalancePlotter();
    private final RadarPlotter radarPlotter = new RadarPlotter();
    private final RankingTable rankingTable = new RankingTable();
    private final ComparisonCsvWriter matrixWriter = new ComparisonCsvWriter();

    public ExportService(ObjectMapper mapper, SessionScanner scanner, CompareService compare) {
        this(mapper, scanner, compare, new RunLayoutResolver());
    }

    public ExportService(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunLayoutResolver resolver) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.resolver = resolver;
    }

    /**
     * 余额曲线 PNG：所有给定会话的所有已落盘 run 叠加成一张，与 {@code evaluate plot --overlay} 同口径。
     *
     * @throws ArtifactNotFoundException 过滤后没有任何真实存在的余额 CSV
     */
    public Export balancePng(List<String> sessions, String title) {
        List<Path> dirs = resolveAll(sessions);
        List<Path> csvFiles = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Path dir : dirs) {
            RunLayout layout = resolver.resolve(dir);
            for (int run : scanner.runIndexes(dir)) {
                Path csv = layout.balanceCsv(run);
                if (Files.isRegularFile(csv)) {
                    csvFiles.add(csv);
                    labels.add(dir.getFileName() + "/run_" + run);
                }
            }
        }
        if (csvFiles.isEmpty()) {
            throw new ArtifactNotFoundException("没有可绘制的余额 CSV");
        }
        return new Export(PNG, "balance.png", balancePlotter.png(csvFiles, labels, title));
    }

    /**
     * 七轴雷达 PNG；与 {@code /api/compare} 的 radar 段同一条计算链。
     *
     * @throws ArtifactNotFoundException 没有可绘制的模型画像（请求合法、只是数据缺失）
     */
    public Export radarPng(List<String> sessions, String title) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        byte[] body;
        try {
            body = radarPlotter.png(profile, title);
        } catch (IllegalArgumentException exception) {
            // 转译范围仅限绘图类：会话名解析已在上面完成，其 IllegalArgumentException 不该落到这里
            throw new ArtifactNotFoundException(String.valueOf(exception.getMessage()));
        }
        return new Export(PNG, "radar.png", body);
    }

    /** 排名表 CSV（{@code rank,model,primary_raw,<七轴>}）。 */
    public Export rankingCsv(List<String> sessions) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        return new Export(
                CSV, "ranking.csv", rankingTable.toCsv(profile).getBytes(StandardCharsets.UTF_8));
    }

    /** 排名表 JSON（含 axes / median / models 三段）。 */
    public Export rankingJson(List<String> sessions) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        return new Export(
                JSON,
                "ranking.json",
                rankingTable.toJson(profile, mapper).getBytes(StandardCharsets.UTF_8));
    }

    /** 指标矩阵长表 CSV（{@code session,metric,mean,std,n}）。 */
    public Export matrixCsv(List<String> sessions) {
        CompareService.Compared compared = compare.compareAll(resolveAll(sessions));
        return new Export(
                CSV,
                "matrix.csv",
                matrixWriter.toLongCsv(compared.report()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 逐个把会话名解析为目录路径。
     *
     * <p><b>必须在调用绘图类之前完成且不包在转译 try 里</b>：非法名要抛
     * {@code IllegalArgumentException}（→400），不存在要抛 {@code ArtifactNotFoundException}（→404）。
     */
    private List<Path> resolveAll(List<String> sessions) {
        List<Path> dirs = new ArrayList<>(sessions.size());
        for (String name : sessions) {
            dirs.add(scanner.sessionDir(name));
        }
        return dirs;
    }
}
