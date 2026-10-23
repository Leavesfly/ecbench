package io.github.ecommercebench.domain.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 供应商目录定义；fraudType 为空表示正常供应商。
 *
 * @param supplierId 供应商唯一标识
 * @param supplierName 供应商名称
 * @param supplierEmail 联系邮箱，谈判对话中展示
 * @param supplierType 供应商类型，"bad" 代表欺诈对手方
 * @param personality 谈判人格描述，影响拒绝与让步行为
 * @param urgency 紧急度（0~1），越高越早接受
 * @param fraudType 欺诈类型标记；空白或 null 时归一为 null（即正常供应商）
 * @param categoriesServed 供货的类别列表，构造时冻结为不可变拷贝
 * @param bankruptcyThreshold 累计拖欠到该额后供应商破产（不再供货）
 */
public record Supplier(
    String supplierId,
    String supplierName,
    String supplierEmail,
    String supplierType,
    String personality,
    double urgency,
    String fraudType,
    List<String> categoriesServed,
    int bankruptcyThreshold) {

  /** 紧凑构造器：锁定非空字段，将供货类别冻结为不可变拷贝，并把空白 fraudType 归一为 null。 */
  public Supplier {
    Objects.requireNonNull(supplierId, "supplierId 不能为空");
    Objects.requireNonNull(supplierType, "supplierType 不能为空");
    categoriesServed = List.copyOf(categoriesServed);
    fraudType = fraudType == null || fraudType.isBlank() ? null : fraudType;
  }

  /** 是否为欺诈供应商，以 supplierType 是否为 "bad" 判定。 */
  public boolean isFraudulent() {
    return "bad".equals(supplierType);
  }
}
