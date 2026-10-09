package io.github.ecommercebench.app.log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 论文 §E.4「八类活动带」的工具→带映射。
 *
 * <p>18 个工具被划分为 8 个互斥的带：5 个单工具带（等待/发货/上架/提现/议价）、状态轮询（3 个只读工具）、记忆（operate_memory）， 以及收纳其余 9
 * 个工具的剩余带（搜索/定价/促销/退货入仓/退货溯源/开关店）。带序固定，使 {@code analysis.json} 的活动带输出稳定、可复现。
 */
public final class ActivityBands {

    /**
     * 固定的 8 个带键，按论文叙述顺序排列。
     */
    public static final List<String> BAND_KEYS =
            List.of(
                    "waiting",
                    "shipping",
                    "listing",
                    "withdraw",
                    "bargaining",
                    "state_polling",
                    "memory",
                    "remainder");

    private static final String REMAINDER = "remainder";

    private static final Map<String, String> TOOL_TO_BAND =
            Map.ofEntries(
                    Map.entry("wait_for_next_day", "waiting"),
                    Map.entry("ship_orders", "shipping"),
                    Map.entry("publish_to_store", "listing"),
                    Map.entry("withdraw", "withdraw"),
                    Map.entry("chatbox", "bargaining"),
                    Map.entry("check_balance", "state_polling"),
                    Map.entry("check_store_status", "state_polling"),
                    Map.entry("check_warehouse", "state_polling"),
                    Map.entry("operate_memory", "memory"));

    private ActivityBands() {
    }

    /**
     * 返回工具所属的带；未显式归类的工具（含剩余 9 类与未来新增工具）落入 {@code remainder}。
     */
    public static String bandOf(String toolName) {
        return TOOL_TO_BAND.getOrDefault(toolName, REMAINDER);
    }

    /**
     * 按固定带序把逐工具计数聚合为逐带计数；所有 8 个带都会出现，无调用的带补 0。
     */
    public static Map<String, Integer> bandCounts(Map<String, Integer> perToolCounts) {
        Map<String, Integer> bands = new LinkedHashMap<>();
        for (String band : BAND_KEYS) {
            bands.put(band, 0);
        }
        for (Map.Entry<String, Integer> entry : perToolCounts.entrySet()) {
            bands.merge(bandOf(entry.getKey()), entry.getValue(), Integer::sum);
        }
        return bands;
    }

    /**
     * 逐工具计数的总调用次数。
     */
    public static int total(Map<String, Integer> perToolCounts) {
        return perToolCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
