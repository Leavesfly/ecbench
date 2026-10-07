package io.github.ecommercebench.simulation.stats;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 供应商触达跟踪，端口 Python 的 {@code contacted_suppliers}（集合）与 {@code supplier_order_count}（键集合）。
 *
 * <p>按供应商名去重记录两类触达：被 agent 至少发过一次消息（contacted）、被成功下过至少一单（ordered）。分桶（好/坏、人格、欺诈型） 在分析时按 catalog
 * 供应商属性完成，本类只保存去重后的名字集合。
 */
public final class SupplierEngagement {

  private final Set<String> contacted = new LinkedHashSet<>();
  private final Set<String> ordered = new LinkedHashSet<>();

  public void recordContacted(String supplierName) {
    if (supplierName != null && !supplierName.isBlank()) {
      contacted.add(supplierName);
    }
  }

  public void recordOrdered(String supplierName) {
    if (supplierName != null && !supplierName.isBlank()) {
      ordered.add(supplierName);
    }
  }

  public Set<String> contacted() {
    return Collections.unmodifiableSet(contacted);
  }

  public Set<String> ordered() {
    return Collections.unmodifiableSet(ordered);
  }
}
