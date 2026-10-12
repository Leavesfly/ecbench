package io.github.ecommercebench.app.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 {@code --log-dir} 下的会话目录，并安全地把会话名解析为目录路径。
 *
 * <p>会话即 logDir 的直接子目录（隐藏目录忽略）；run 数取 {@code metrics/run_<i>_analysis.json} 的个数；模型键取目录名最后一个下划线之后的段。
 * 名称校验拒绝路径穿越，且解析结果必须仍落在 logDir 之内，避免看板被越界路径读取仓库外文件。
 */
public final class SessionScanner {

  /** 会话名允许字符集：字母数字与点、下划线、连字符。 */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

  private static final Pattern ANALYSIS = Pattern.compile("run_(\\d+)_analysis\\.json");

  private final Path logDir;

  public SessionScanner(Path logDir) {
    this.logDir = logDir.toAbsolutePath().normalize();
  }

  /** 按名称升序返回全部会话概览；logDir 不存在时返回空列表。 */
  public List<SessionInfo> scan() {
    if (!Files.isDirectory(logDir)) {
      return List.of();
    }
    List<SessionInfo> result = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(logDir)) {
      dirs.filter(Files::isDirectory)
          .filter(dir -> !dir.getFileName().toString().startsWith("."))
          .map(this::toInfo)
          .sorted(Comparator.comparing(SessionInfo::name))
          .forEach(result::add);
    } catch (IOException exception) {
      throw new UncheckedIOException("无法列出日志目录: " + logDir, exception);
    }
    return result;
  }

  /**
   * 把会话名解析为目录路径。
   *
   * @throws IllegalArgumentException 名称非法（含 {@code ..}、斜杠或非法字符）或目录不存在／越界
   */
  public Path sessionDir(String name) {
    if (!isValidName(name)) {
      throw new IllegalArgumentException("非法会话名: " + name);
    }
    Path resolved = logDir.resolve(name).normalize();
    if (!resolved.startsWith(logDir) || !Files.isDirectory(resolved)) {
      throw new IllegalArgumentException("会话不存在或越界: " + name);
    }
    return resolved;
  }

  /** 名称仅允许字母数字与点／下划线／连字符，且不含 {@code ..}。 */
  public static boolean isValidName(String name) {
    return name != null && !name.contains("..") && NAME.matcher(name).matches();
  }

  private SessionInfo toInfo(Path dir) {
    String name = dir.getFileName().toString();
    List<Integer> runs = runIndexes(dir);
    boolean hasBalance = Files.isDirectory(dir.resolve("balance")) && !runs.isEmpty();
    return new SessionInfo(name, modelKey(name), runs.size(), hasBalance);
  }

  /** 从 {@code metrics/} 子目录收集 run 序号并升序返回。 */
  private List<Integer> runIndexes(Path dir) {
    Path metrics = dir.resolve("metrics");
    if (!Files.isDirectory(metrics)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(metrics)) {
      return files
          .map(file -> file.getFileName().toString())
          .map(ANALYSIS::matcher)
          .filter(Matcher::matches)
          .map(matcher -> Integer.parseInt(matcher.group(1)))
          .sorted()
          .toList();
    } catch (IOException exception) {
      throw new UncheckedIOException("无法列出 metrics 目录: " + metrics, exception);
    }
  }

  private static String modelKey(String name) {
    int separator = name.lastIndexOf('_');
    return separator >= 0 && separator < name.length() - 1 ? name.substring(separator + 1) : name;
  }
}
