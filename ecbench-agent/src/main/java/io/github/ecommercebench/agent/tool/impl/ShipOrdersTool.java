package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.ShipResult;
import io.github.ecommercebench.simulation.dto.ShippedOrder;
import io.github.ecommercebench.simulation.state.PendingShipment;
import io.github.ecommercebench.simulation.state.ShipSpeed;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ship_orders：action=list 查看待发货队列，action=ship 发货，输出逐键对齐 Python
 * `ship_orders`/`get_pending_shipments`。
 */
public final class ShipOrdersTool implements EcommerceTool {

  private static final String NAME = "ship_orders";
  private static final String LIST_NOTE =
      "Ship with ship_orders (speed: fast/standard/slow). Faster ship costs more but reduces "
          + "returns; slow is cheap but raises returns. Unshipped orders cancel after the deadline "
          + "(lost sale + reputation hit).";

  private final ToolDefinition definition = ToolSchemas.load(NAME);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ToolDefinition definition() {
    return definition;
  }

  @Override
  public ObjectNode execute(JsonNode args, ToolExecutionContext context) {
    String action = ToolArgs.string(args, "action", "ship");
    if ("list".equals(action)) {
      return listPending(context);
    }
    return ship(args, context);
  }

  private ObjectNode listPending(ToolExecutionContext context) {
    List<PendingShipment> pending = context.engine().listPendingShipments();
    LocalDate today = context.engine().currentDate();
    ObjectNode out = context.mapper().createObjectNode();
    ArrayNode items = out.putArray("pending_shipments");
    for (PendingShipment shipment : pending) {
      ObjectNode node = items.addObject();
      node.put("shipment_id", shipment.shipmentId());
      node.put("store_id", shipment.storeId());
      node.put("product_id", shipment.productId());
      node.put("quantity", shipment.quantity());
      node.put("unit_price", shipment.unitPrice().amount().doubleValue());
      node.put("revenue_net_if_shipped", shipment.revenueNet().amount().doubleValue());
      node.put("sale_date", shipment.saleDate().toString());
      node.put("ship_deadline", shipment.deadline().toString());
      node.put("days_left", ChronoUnit.DAYS.between(today, shipment.deadline()));
    }
    out.put("count", pending.size());
    out.put("note", LIST_NOTE);
    return out;
  }

  private ObjectNode ship(JsonNode args, ToolExecutionContext context) {
    ObjectNode out = context.mapper().createObjectNode();
    String speedName = ToolArgs.string(args, "speed", "standard").toLowerCase(Locale.ROOT);
    ShipSpeed speed;
    try {
      speed = ShipSpeed.fromWireName(speedName);
    } catch (IllegalArgumentException e) {
      out.put("success", false);
      out.put("error", "Invalid speed '" + speedName + "'. Choose: [fast, standard, slow].");
      return out;
    }
    List<Long> shipmentIds = null;
    JsonNode idsNode = args.get("shipment_ids");
    if (idsNode != null && idsNode.isArray()) {
      shipmentIds = new ArrayList<>();
      for (JsonNode node : idsNode) {
        shipmentIds.add(node.asLong());
      }
    }
    ShipResult result = context.engine().shipOrders(shipmentIds, speed);
    if (!result.success()) {
      out.put("success", false);
      out.put("error", result.error());
      return out;
    }
    out.put("success", true);
    out.put("speed", result.speed().wireName());
    out.put("shipped_count", result.shippedCount());
    out.put("total_shipping_cost", result.totalShippingCost().amount().doubleValue());
    out.put("total_revenue_into_escrow", result.totalRevenueIntoEscrow().amount().doubleValue());
    out.put("bank_balance", result.bankBalance().amount().doubleValue());
    out.put("pending_settlement", result.pendingSettlement().amount().doubleValue());
    ArrayNode shipments = out.putArray("shipments");
    for (ShippedOrder order : result.shipments()) {
      ObjectNode node = shipments.addObject();
      node.put("shipment_id", order.shipmentId());
      node.put("product_id", order.productId());
      node.put("quantity", order.quantity());
      node.put("ship_cost", order.shippingCost().amount().doubleValue());
      node.put("revenue_into_escrow", order.revenueIntoEscrow().amount().doubleValue());
      node.put("settles_on", order.settlesOn().toString());
    }
    return out;
  }
}
