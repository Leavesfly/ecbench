package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 八类活动带测试：断言 18 个工具按论文 §E.4 恰好划分进 8 个带（5 个单工具带 + 状态轮询 3 + 记忆 1 + 其余 9），且 {@code bandCounts}
 * 以固定带序聚合、空带补 0。
 */
class ActivityBandsTest {

    /**
     * 与 tool-schema 目录一致的 18 个工具线格式名。
     */
    private static final Set<String> ALL_TOOLS =
            Set.of(
                    "chatbox",
                    "check_balance",
                    "check_store_status",
                    "check_warehouse",
                    "close_store",
                    "join_promotion",
                    "list_products",
                    "market_search",
                    "open_store",
                    "operate_memory",
                    "publish_to_store",
                    "return_to_warehouse",
                    "set_prices",
                    "ship_orders",
                    "supplier_search",
                    "trace_return_sources",
                    "wait_for_next_day",
                    "withdraw");

    @Test
    void bandKeysAreTheEightPaperBandsInOrder() {
        assertThat(ActivityBands.BAND_KEYS)
                .containsExactly(
                        "waiting",
                        "shipping",
                        "listing",
                        "withdraw",
                        "bargaining",
                        "state_polling",
                        "memory",
                        "remainder");
    }

    @Test
    void fiveSingleToolBandsMapOneToolEach() {
        assertThat(ActivityBands.bandOf("wait_for_next_day")).isEqualTo("waiting");
        assertThat(ActivityBands.bandOf("ship_orders")).isEqualTo("shipping");
        assertThat(ActivityBands.bandOf("publish_to_store")).isEqualTo("listing");
        assertThat(ActivityBands.bandOf("withdraw")).isEqualTo("withdraw");
        assertThat(ActivityBands.bandOf("chatbox")).isEqualTo("bargaining");
    }

    @Test
    void statePollingAndMemoryBandsMapTheirTools() {
        assertThat(ActivityBands.bandOf("check_balance")).isEqualTo("state_polling");
        assertThat(ActivityBands.bandOf("check_store_status")).isEqualTo("state_polling");
        assertThat(ActivityBands.bandOf("check_warehouse")).isEqualTo("state_polling");
        assertThat(ActivityBands.bandOf("operate_memory")).isEqualTo("memory");
    }

    @Test
    void remainderBandHoldsTheOtherNineTools() {
        Set<String> remainder =
                Set.of(
                        "close_store",
                        "join_promotion",
                        "list_products",
                        "market_search",
                        "open_store",
                        "return_to_warehouse",
                        "set_prices",
                        "supplier_search",
                        "trace_return_sources");
        for (String tool : remainder) {
            assertThat(ActivityBands.bandOf(tool)).isEqualTo("remainder");
        }
    }

    @Test
    void everyToolMapsToAKnownBand() {
        for (String tool : ALL_TOOLS) {
            assertThat(ActivityBands.BAND_KEYS).contains(ActivityBands.bandOf(tool));
        }
    }

    @Test
    void bandCountsAggregateInFixedOrderWithZeroFill() {
        Map<String, Integer> perTool = new LinkedHashMap<>();
        perTool.put("chatbox", 4);
        perTool.put("check_balance", 2);
        perTool.put("check_warehouse", 1);
        perTool.put("operate_memory", 3);
        perTool.put("set_prices", 5);

        Map<String, Integer> bands = ActivityBands.bandCounts(perTool);

        assertThat(bands.keySet()).containsExactlyElementsOf(ActivityBands.BAND_KEYS);
        assertThat(bands.get("bargaining")).isEqualTo(4);
        assertThat(bands.get("state_polling")).isEqualTo(3);
        assertThat(bands.get("memory")).isEqualTo(3);
        assertThat(bands.get("remainder")).isEqualTo(5);
        assertThat(bands.get("waiting")).isZero();
        assertThat(bands.get("shipping")).isZero();
        assertThat(bands.get("listing")).isZero();
        assertThat(bands.get("withdraw")).isZero();
    }

    @Test
    void bandCountsOfEmptyInputYieldsAllZeroBands() {
        Map<String, Integer> bands = ActivityBands.bandCounts(Map.of());
        assertThat(bands.keySet()).containsExactlyElementsOf(ActivityBands.BAND_KEYS);
        assertThat(bands.values()).allSatisfy(value -> assertThat(value).isZero());
    }

    @Test
    void totalSumsAllToolCalls() {
        assertThat(ActivityBands.total(Map.of("chatbox", 4, "withdraw", 6))).isEqualTo(10);
        assertThat(ActivityBands.total(Map.of())).isZero();
    }

    @Test
    void unknownToolFallsIntoRemainderBand() {
        assertThat(ActivityBands.bandOf("some_future_tool")).isEqualTo("remainder");
        assertThat(ActivityBands.bandCounts(Map.of("some_future_tool", 2)).get("remainder"))
                .isEqualTo(2);
    }

    @Test
    void bandKeysListIsImmutable() {
        List<String> keys = ActivityBands.BAND_KEYS;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
