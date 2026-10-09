package io.github.ecommercebench.opponent.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.NegotiationAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 chatbox 消息中的一个或多个 fenced negotiate JSON 块。
 */
public final class NegotiationBlockParser {

    private static final Pattern BLOCK = Pattern.compile("```negotiate\\s*([\\s\\S]*?)```");
    private final ObjectMapper objectMapper;

    public NegotiationBlockParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 抽取正文中所有以 negotiate 标记的 fenced 代码块并解析为动作列表，同时返回剔除这些块后的剩余文本。 */
    public ParsedNegotiation parse(String content) {
        Matcher matcher = BLOCK.matcher(content == null ? "" : content);
        List<NegotiationAction> actions = new ArrayList<>();
        while (matcher.find()) {
            for (JsonNode node : parseFlexible(matcher.group(1).trim())) {
                NegotiationAction action = toAction(node);
                if (action != null) {
                    actions.add(action);
                }
            }
        }
        return new ParsedNegotiation(actions, matcher.replaceAll("").trim());
    }

    /**
     * 宽容解析围栏内容：先按 JSON 流读取（兼容数组/多对象）；失败则退化为逐行 NDJSON， 跳过空行与坏行（与 Python 解析器行为一致），逐条收集合法对象。
     */
    private List<JsonNode> parseFlexible(String raw) {
        try {
            com.fasterxml.jackson.databind.MappingIterator<JsonNode> iterator =
                    objectMapper.readerFor(JsonNode.class).readValues(raw);
            List<JsonNode> values = new ArrayList<>();
            while (iterator.hasNextValue()) {
                JsonNode parsed = iterator.nextValue();
                if (parsed.isArray()) {
                    parsed.forEach(values::add);
                } else if (parsed.isObject()) {
                    values.add(parsed);
                }
            }
            return values;
        } catch (JsonProcessingException ignored) {
            List<JsonNode> values = new ArrayList<>();
            for (String line : raw.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line.trim());
                    if (node.isObject()) {
                        values.add(node);
                    }
                } catch (JsonProcessingException malformedLine) {
                    // 与 Python 解析器一致：忽略坏行，继续解析同一代码块中的其他 NDJSON 行。
                }
            }
            return values;
        } catch (java.io.IOException exception) {
            return List.of();
        }
    }

    /** 将单个 JSON 节点转为谈判动作：按 action 字段分派 offer/accept/reject，缺 sku_id 或 offer 缺价时丢弃。 */
    private NegotiationAction toAction(JsonNode node) {
        String action = node.path("action").asText("").toLowerCase(Locale.ROOT);
        String sku = node.path("sku_id").asText("").trim().toLowerCase(Locale.ROOT);
        if (sku.isEmpty()) {
            return null;
        }
        int quantity = node.path("quantity").canConvertToInt() ? node.path("quantity").asInt() : 1;
        return switch (action) {
            case "offer" -> node.has("price") && node.get("price").isNumber()
                    ? new NegotiationAction.Offer(sku, Money.of(node.get("price").asDouble()), quantity)
                    : null;
            case "accept" -> new NegotiationAction.Accept(
                    sku,
                    node.has("price") && node.get("price").isNumber()
                            ? Money.of(node.get("price").asDouble())
                            : null,
                    quantity,
                    node.has("shipping_address") ? node.get("shipping_address").asText() : null);
            case "reject" -> new NegotiationAction.Reject(sku, quantity);
            default -> null;
        };
    }
}
