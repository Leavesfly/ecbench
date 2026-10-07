package io.github.ecommercebench.evaluation.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 指标/分析 JSON 读取器。
 *
 * <p>把 {@code run_{idx}_analysis.json} 或 {@code run_{idx}_negotiation_metrics.json} 解析为 {@link
 * JsonNode} 树， 供比较器与报告按 JSON path 取值。未知字段天然被忽略（树模型）；读取失败抛出含文件路径的异常。
 */
public final class AnalysisJsonReader {

  private final ObjectMapper mapper;

  public AnalysisJsonReader() {
    this(new ObjectMapper());
  }

  public AnalysisJsonReader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public JsonNode read(Path json) {
    try {
      return mapper.readTree(json.toFile());
    } catch (IOException exception) {
      throw new UncheckedIOException("无法读取分析 JSON: " + json, exception);
    }
  }
}
