package io.github.ecommercebench.domain.catalog;

import java.util.List;
import java.util.Map;

/**
 * market_search 使用的静态定性指引。
 *
 * <p>端口自 Python `store_type_config.STORE_PLAYBOOK` 与 `EcommerceEnv` 的 PROFIT_POTENTIAL_BY_TIER /
 * STORE_ADVANTAGE_AXIS 常量；这些是给 Agent 的非数值提示层，不参与任何经济计算。
 *
 * @param profitPotentialByTier 按难度层级索引的利润潜力文案
 * @param storeAdvantageAxis 按店型索引的优势轴文案
 * @param playbook 按店型索引的经营剧本（优势/挑战/提示）
 */
public record MarketGuidance(
    Map<Integer, String> profitPotentialByTier,
    Map<String, String> storeAdvantageAxis,
    Map<String, StorePlaybook> playbook) {

  public MarketGuidance {
    profitPotentialByTier = Map.copyOf(profitPotentialByTier);
    storeAdvantageAxis = Map.copyOf(storeAdvantageAxis);
    playbook = Map.copyOf(playbook);
  }

  /** 返回一份三张表均为空的指引，用于未配置 playbook 文件的场景。 */
  public static MarketGuidance empty() {
    return new MarketGuidance(Map.of(), Map.of(), Map.of());
  }

  /** 按难度层级返回利润潜力文案，未知层级返回空串。 */
  public String profitPotential(int tier) {
    return profitPotentialByTier.getOrDefault(tier, "");
  }

  /** 按店型返回优势轴文案，未知店型返回空串。 */
  public String storeAdvantage(String storeType) {
    return storeAdvantageAxis.getOrDefault(storeType, "");
  }

  /** 按店型返回剧本，未知店型返回空剧本。 */
  public StorePlaybook playbook(String storeType) {
    return playbook.getOrDefault(storeType, StorePlaybook.EMPTY);
  }

  /**
   * 单个店型的优势、挑战与经营提示。
   *
   * @param strengths 优势列表，构造时冻结为不可变拷贝
   * @param challenges 挑战列表，构造时冻结为不可变拷贝
   * @param tips 经营提示列表，构造时冻结为不可变拷贝
   */
  public record StorePlaybook(List<String> strengths, List<String> challenges, List<String> tips) {

    /** 空剧本常量，用于未知店型的默认回退。 */
    public static final StorePlaybook EMPTY = new StorePlaybook(List.of(), List.of(), List.of());

    /** 紧凑构造器：将三个字符串列表冻结为不可变拷贝。 */
    public StorePlaybook {
      strengths = List.copyOf(strengths);
      challenges = List.copyOf(challenges);
      tips = List.copyOf(tips);
    }
  }
}
