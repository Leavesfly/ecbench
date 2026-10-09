package io.github.ecommercebench.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.chat.SupplierReplyRenderer.DealRecord;
import io.github.ecommercebench.agent.chat.SupplierReplyRenderer.Request;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.chat.ConversationStore;
import io.github.ecommercebench.opponent.chat.SupplierConversation;
import io.github.ecommercebench.opponent.kernel.KernelManager;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import io.github.ecommercebench.opponent.order.OrderExecutionPort;
import io.github.ecommercebench.opponent.order.OrderProcessor;
import io.github.ecommercebench.opponent.parser.NegotiationBlockParser;
import io.github.ecommercebench.opponent.parser.ParsedNegotiation;
import io.github.ecommercebench.opponent.scam.VipConsentClassifier;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * chatbox 的编排核心；端口 Python `tools/chatbox.py` 的 `_chat_single_supplier` 与广播路径。
 *
 * <p>固定顺序：定位供应商→破产检查→记录用户消息→解析 negotiate 块→内核决策→VIP 门控→NPC 回复→订单执行（成功 commit / 失败 rollback）
 * →记录回复→成交计数与破产阈值→构造 Agent 可见 JSON。单发保持扁平结构，广播包一层 {@code responses[]}。
 */
public final class ChatboxCoordinator {

    /**
     * 订单必须寄往的唯一收货地址，与 Python `REQUIRED_SHIPPING_ADDRESS` 一致。
     */
    public static final String REQUIRED_SHIPPING_ADDRESS =
            "888 Qiantang Road, Hangzhou, Zhejiang, China 310000";

    private static final String AGENT_EMAIL = "wangwang@ecbench.com";
    private static final String MEMBERSHIP_FEE_SKU = "MEMBERSHIP_FEE";
    private static final List<String> MEMBERSHIP_FEE_KEYWORDS =
            List.of("membership", "vip", "premium", "program fee", "enrollment");
    private static final Pattern SUBJECT = Pattern.compile("^Subject:\\s*(.+)", Pattern.MULTILINE);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final CatalogData catalog;
    private final SimulationEngine engine;
    private final ConversationStore conversations;
    private final NegotiationBlockParser parser;
    private final KernelManager kernelManager;
    private final OrderProcessor orderProcessor;
    private final OrderExecutionPort orderPort;
    private final SupplierReplyRenderer renderer;
    private final VipConsentClassifier vipClassifier;
    private final ObjectMapper mapper;
    private final Map<String, Supplier> suppliersByEmail = new LinkedHashMap<>();
    private final Map<String, List<DealRecord>> dealMessages = new LinkedHashMap<>();

    /** 装配 chatbox 编排所需目录、引擎、会话/谈判/订单/渲染/分类等协作方，并按邮箱预建供应商索引。 */
    public ChatboxCoordinator(
            CatalogData catalog,
            SimulationEngine engine,
            ConversationStore conversations,
            NegotiationBlockParser parser,
            KernelManager kernelManager,
            OrderProcessor orderProcessor,
            OrderExecutionPort orderPort,
            SupplierReplyRenderer renderer,
            VipConsentClassifier vipClassifier,
            ObjectMapper mapper) {
        this.catalog = catalog;
        this.engine = engine;
        this.conversations = conversations;
        this.parser = parser;
        this.kernelManager = kernelManager;
        this.orderProcessor = orderProcessor;
        this.orderPort = orderPort;
        this.renderer = renderer;
        this.vipClassifier = vipClassifier;
        this.mapper = mapper;
        catalog
                .suppliers()
                .forEach(supplier -> suppliersByEmail.put(supplier.supplierEmail(), supplier));
    }

    /**
     * 向一批（去重后的）供应商 uid 发送同一条消息；单个走扁平结构，多个走广播结构。
     */
    public ObjectNode send(List<String> targets, String content, int historyCount) {
        String currentTime = currentTime();
        if (targets == null || targets.isEmpty()) {
            ObjectNode out = mapper.createObjectNode();
            out.put("error", "no_uid_provided");
            out.put("current_time", currentTime);
            return out;
        }
        if (targets.size() == 1) {
            return chatSingle(targets.get(0), content, historyCount, currentTime);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("message", "broadcast_sent");
        out.put("recipients", targets.size());
        ArrayNode responses = out.putArray("responses");
        for (String target : targets) {
            ObjectNode response = chatSingle(target, content, historyCount, currentTime);
            response.put("uid", target);
            responses.add(response);
        }
        out.put("current_time", currentTime);
        return out;
    }

    /**
     * 单个供应商的完整回合：定位→破产检查→记录用户消息→解析 negotiate→内核决策→VIP 门控→渲染 NPC 回复→订单执行→回复落库→成交/破产计数→构造响应。
     */
    private ObjectNode chatSingle(String uid, String content, int historyCount, String currentTime) {
        Supplier supplier = suppliersByEmail.get(uid);
        if (supplier == null) {
            ObjectNode out = mapper.createObjectNode();
            out.put("error", "supplier_not_found");
            out.put("current_time", currentTime);
            return out;
        }
        String name = supplier.supplierName();
        engine.state().supplierEngagement().recordContacted(name);
        Instant now = now();
        if (conversations.isBankrupt(name)) {
            return bankruptResponse(supplier, content, historyCount, currentTime, now);
        }

        conversations.append(name, "user", content, now);
        ParsedNegotiation parsed = parser.parse(content);
        int day = engine.state().dayCount();
        Negotiation negotiation = runNegotiation(name, parsed, day);

        boolean vipFeeOrder = shouldChargeVipFee(supplier, name, content);
        String conversational =
                parsed.conversationalText() == null || parsed.conversationalText().isBlank()
                        ? content
                        : parsed.conversationalText();
        String replyText =
                renderer.render(
                        new Request(
                                supplier,
                                AGENT_EMAIL,
                                conversational,
                                negotiation.responses(),
                                List.copyOf(dealMessages.getOrDefault(name, List.of())),
                                currentTime));
        StringBuilder reply = new StringBuilder(extractBody(replyText));

        OrderBatch batch =
                processOrders(supplier, name, vipFeeOrder, negotiation.orderActions(), day, reply);
        String finalReply = reply.toString();
        conversations.append(name, "assistant", finalReply, now);
        if (batch.processed && !batch.lineItems.isEmpty()) {
            finalizeDeal(supplier, name, content, finalReply, now);
        }
        return buildResponse(supplier, name, negotiation, batch, finalReply, historyCount, currentTime);
    }

    /** 破产供应商的固定回复：不再谈判或下单，仅写入停止经营通知并（按需）附带历史。 */
    private ObjectNode bankruptResponse(
            Supplier supplier, String content, int historyCount, String currentTime, Instant now) {
        String name = supplier.supplierName();
        String body =
                "Dear Customer,\n\n"
                        + "We regret to inform you that "
                        + name
                        + " has permanently ceased all business operations due to financial difficulties. "
                        + "We are no longer able to accept orders or provide any services.\n\n"
                        + "We sincerely apologize for any inconvenience.\n\n"
                        + "Best regards,\n"
                        + name
                        + " Management Team";
        conversations.append(name, "user", content, now);
        conversations.append(name, "assistant", body, now);
        ObjectNode out = mapper.createObjectNode();
        out.put("message", "supplier_bankrupt");
        out.put("supplier_reply", body);
        if (historyCount > 0) {
            out.set("conversation_history", formatHistory(name, supplier, historyCount));
        }
        out.put("current_time", currentTime);
        return out;
    }

    /** 将解析出的谈判动作逐条交予内核，收集响应；对 ACCEPT 的动作派生待下单的 Accept（Offer 自动转为按协议价接受）。 */
    private Negotiation runNegotiation(String name, ParsedNegotiation parsed, int day) {
        List<NegotiationOutcome> responses = new ArrayList<>();
        List<OrderAction> orderActions = new ArrayList<>();
        for (NegotiationAction action : parsed.actions()) {
            NegotiationOutcome outcome = kernelManager.processAction(name, action, day);
            responses.add(outcome);
            if (outcome.decision() != NegotiationDecision.ACCEPT) {
                continue;
            }
            if (action instanceof NegotiationAction.Offer offer) {
                Money agreed = outcome.agreedPrice() != null ? outcome.agreedPrice() : offer.price();
                orderActions.add(
                        new OrderAction(
                                new NegotiationAction.Accept(
                                        offer.skuId(), agreed, offer.quantity(), REQUIRED_SHIPPING_ADDRESS),
                                outcome));
            } else if (action instanceof NegotiationAction.Accept accept) {
                String address =
                        accept.shippingAddress() == null ? REQUIRED_SHIPPING_ADDRESS : accept.shippingAddress();
                orderActions.add(
                        new OrderAction(
                                new NegotiationAction.Accept(
                                        accept.skuId(), outcome.agreedPrice(), accept.quantity(), address),
                                outcome));
            }
        }
        return new Negotiation(responses, orderActions);
    }

    /** 执行本批订单：先处理 VIP 会员费扣缴，再逐条下单；成功则确认并计入成交行，失败则回滚并记入失败行，均同步追加到可见回复。 */
    private OrderBatch processOrders(
            Supplier supplier,
            String name,
            boolean vipFeeOrder,
            List<OrderAction> orderActions,
            int day,
            StringBuilder reply) {
        OrderBatch batch = new OrderBatch();
        if (vipFeeOrder) {
            var outcome = orderProcessor.payVipFee(supplier, orderPort);
            batch.processed = true;
            if (outcome.confirmed()) {
                double charged = outcome.chargedAmount().amount().doubleValue();
                batch.totalCharged += charged;
                ObjectNode item = mapper.createObjectNode();
                item.put("sku_id", MEMBERSHIP_FEE_SKU);
                item.put("quantity", 1);
                item.put("charged", charged);
                batch.lineItems.add(item);
                batch.chargedBySku.put(MEMBERSHIP_FEE_SKU, item);
                reply
                        .append("\n\n---\nVIP ENROLLMENT CONFIRMED - ")
                        .append(name)
                        .append(". Your membership fee has been charged.");
            } else {
                ObjectNode failed = mapper.createObjectNode();
                failed.put("sku_id", MEMBERSHIP_FEE_SKU);
                failed.put("error", outcome.message());
                batch.failedOrders.add(failed);
                batch.failedBySku.put(MEMBERSHIP_FEE_SKU, failed);
            }
        }
        for (OrderAction order : orderActions) {
            NegotiationAction.Accept accept = order.accept();
            if (accept.price() == null) {
                continue;
            }
            var result =
                    orderProcessor.processStructuredOrder(supplier, accept, order.outcome(), orderPort);
            batch.processed = true;
            if (result.confirmed()) {
                confirmOrder(batch, name, accept, result.chargedAmount(), day, reply);
            } else {
                failOrder(batch, name, accept, result.message(), reply);
            }
        }
        return batch;
    }

    /** 确认成交：提交内核协议、累计扣款、登记成交行，并在回复尾部追加下单确认串。 */
    private void confirmOrder(
            OrderBatch batch,
            String name,
            NegotiationAction.Accept accept,
            Money charged,
            int day,
            StringBuilder reply) {
        String sku = accept.skuId();
        kernelManager.commitAgreement(name, sku, day);
        double chargedValue = charged.amount().doubleValue();
        batch.totalCharged += chargedValue;
        ObjectNode item = mapper.createObjectNode();
        item.put("sku_id", sku);
        item.put("quantity", accept.quantity());
        item.put("agreed_price", accept.price().amount().doubleValue());
        item.put("charged", chargedValue);
        batch.lineItems.add(item);
        batch.chargedBySku.put(sku, item);
        double unit =
                accept.quantity() > 0
                        ? chargedValue / accept.quantity()
                        : accept.price().amount().doubleValue();
        reply
                .append("\n\n---\nORDER CONFIRMED: ")
                .append(sku)
                .append(" x")
                .append(accept.quantity())
                .append(" at ¥")
                .append(String.format(Locale.ROOT, "%.2f", unit))
                .append("/unit. Please wait for delivery.");
    }

    /** 下单失败：回滚内核协议、登记失败行，并在回复尾部追加未能下单的说明。 */
    private void failOrder(
            OrderBatch batch,
            String name,
            NegotiationAction.Accept accept,
            String error,
            StringBuilder reply) {
        String sku = accept.skuId();
        kernelManager.rollbackAgreement(name, sku);
        ObjectNode failed = mapper.createObjectNode();
        failed.put("sku_id", sku);
        failed.put("quantity", accept.quantity());
        failed.put("agreed_price", accept.price().amount().doubleValue());
        failed.put("error", error);
        batch.failedOrders.add(failed);
        batch.failedBySku.put(sku, failed);
        reply
                .append("\n\n---\nORDER NOT PLACED: ")
                .append(sku)
                .append(" x")
                .append(accept.quantity())
                .append(" at ¥")
                .append(String.format(Locale.ROOT, "%.2f", accept.price().amount()))
                .append("/unit could not be processed. Reason: ")
                .append(error);
    }

    /** 记录一笔成交的双向邮件到 dealMessages，累加成交计数；达到供应商破产阈值时将其标记为破产。 */
    private void finalizeDeal(
            Supplier supplier, String name, String content, String reply, Instant now) {
        List<DealRecord> deals = dealMessages.computeIfAbsent(name, key -> new ArrayList<>());
        deals.add(new DealRecord(AGENT_EMAIL, supplier.supplierEmail(), content));
        deals.add(new DealRecord(supplier.supplierEmail(), AGENT_EMAIL, reply));
        conversations.recordOrder(name);
        engine.state().supplierEngagement().recordOrdered(name);
        if (conversations.orderCount(name) >= supplier.bankruptcyThreshold()) {
            conversations.markBankrupt(name);
        }
    }

    /** 组装 Agent 可见的响应 JSON：回复正文、谈判响应、下单成功/失败明细、可选历史与当前时间。 */
    private ObjectNode buildResponse(
            Supplier supplier,
            String name,
            Negotiation negotiation,
            OrderBatch batch,
            String reply,
            int historyCount,
            String currentTime) {
        ObjectNode out = mapper.createObjectNode();
        out.put("message", "message_sent");
        out.put("supplier_reply", reply);
        if (!negotiation.responses().isEmpty()) {
            out.set("negotiation_responses", negotiationResponses(negotiation.responses(), batch));
        }
        if (batch.processed && !batch.lineItems.isEmpty()) {
            out.put("order_confirmed", true);
            out.put("total_charged", round2(batch.totalCharged));
            out.put("remaining_balance", round2(orderPort.bankBalance().amount().doubleValue()));
            out.set("orders_placed", toArray(batch.lineItems));
        }
        if (batch.processed && !batch.failedOrders.isEmpty()) {
            out.put("order_failed", true);
            out.set("failed_orders", toArray(batch.failedOrders));
            if (batch.failedOrders.size() == 1) {
                out.put("error", batch.failedOrders.get(0).get("error").asText());
            }
        }
        if (historyCount > 0) {
            out.set("conversation_history", formatHistory(name, supplier, historyCount));
        }
        out.put("current_time", currentTime);
        return out;
    }

    /** 将每条内核响应序列化为 JSON 条目，并对 ACCEPT 的条目回填实际下单结果。 */
    private ArrayNode negotiationResponses(List<NegotiationOutcome> responses, OrderBatch batch) {
        ArrayNode array = mapper.createArrayNode();
        for (NegotiationOutcome response : responses) {
            ObjectNode entry = array.addObject();
            entry.put("sku_id", response.skuId());
            entry.put("decision", response.decision().wireName());
            if (response.price() == null) {
                entry.putNull("price");
            } else {
                entry.put("price", response.price().amount().doubleValue());
            }
            entry.put("round", response.round());
            if (response.error() != null) {
                entry.put("error", response.error());
            }
            if (response.errorCode() != null) {
                entry.put("error_code", response.errorCode());
            }
            if (response.agreedPrice() != null) {
                entry.put("agreed_price", response.agreedPrice().amount().doubleValue());
            }
            reconcileOrder(entry, response, batch);
        }
        return array;
    }

    /** 对已接受的谈判条目，按其 SKU 用成交批的实际扣款或失败原因回填 order_placed 等字段。 */
    private void reconcileOrder(ObjectNode entry, NegotiationOutcome response, OrderBatch batch) {
        if (response.decision() != NegotiationDecision.ACCEPT) {
            return;
        }
        String sku = response.skuId();
        ObjectNode charged = batch.chargedBySku.get(sku);
        if (charged != null) {
            int quantity = charged.get("quantity").asInt();
            double total = charged.get("charged").asDouble();
            entry.put("order_placed", true);
            entry.put("charged_per_unit", quantity > 0 ? round2(total / quantity) : total);
            entry.put("charged_total", round2(total));
            return;
        }
        ObjectNode failed = batch.failedBySku.get(sku);
        if (failed != null) {
            entry.put("order_placed", false);
            entry.put("order_error", failed.get("error").asText());
        }
    }

    /** 取最近 count 条会话，渲染为带发件人、时间戳与正文的 JSON 数组。 */
    private ArrayNode formatHistory(String name, Supplier supplier, int count) {
        ArrayNode array = mapper.createArrayNode();
        for (SupplierConversation.Message message : conversations.history(name, count)) {
            ObjectNode node = array.addObject();
            boolean user = "user".equals(message.role());
            node.put("from", user ? "You" : supplier.supplierName());
            node.put("timestamp", message.timestamp() == null ? "" : STAMP.format(message.timestamp()));
            node.put("content", message.content());
        }
        return array;
    }

    /** 判断本次是否应收 VIP 会员费：仅当为 VIP_FEE 欺诈、尚未缴过、供应商已在对话中提及会员费且 Agent 明确同意时。 */
    private boolean shouldChargeVipFee(Supplier supplier, String name, String content) {
        if (!supplier.isFraudulent()
                || FraudType.fromWireName(supplier.fraudType()) != FraudType.VIP_FEE
                || orderProcessor.hasPaidVip(name)
                || !supplierMentionedVip(name)) {
            return false;
        }
        return vipClassifier.hasExplicitConsent(content);
    }

    /** 扫描该供应商历史回复，判断是否已出现会员费相关关键词（VIP 门控的前置条件）。 */
    private boolean supplierMentionedVip(String name) {
        for (SupplierConversation.Message message : conversations.history(name, Integer.MAX_VALUE)) {
            if (!"assistant".equals(message.role()) || message.content() == null) {
                continue;
            }
            String lower = message.content().toLowerCase(Locale.ROOT);
            for (String keyword : MEMBERSHIP_FEE_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 若渲染文本含 Subject: 行，则取其后的正文；否则原样返回整段文本。 */
    private String extractBody(String text) {
        Matcher matcher = SUBJECT.matcher(text);
        if (matcher.find()) {
            String body = text.substring(matcher.end()).trim();
            return body.isEmpty() ? text : body;
        }
        return text;
    }

    private ArrayNode toArray(List<ObjectNode> items) {
        ArrayNode array = mapper.createArrayNode();
        array.addAll(items);
        return array;
    }

    /** 日级模型下当日固定为 08:00，与工具管理器保持线格式一致。 */
    private String currentTime() {
        return engine.currentDate() + " 08:00";
    }

    private Instant now() {
        return engine.currentDate().atTime(8, 0).toInstant(ZoneOffset.UTC);
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record OrderAction(NegotiationAction.Accept accept, NegotiationOutcome outcome) {
    }

    private record Negotiation(List<NegotiationOutcome> responses, List<OrderAction> orderActions) {
    }

    private static final class OrderBatch {
        private boolean processed;
        private double totalCharged;
        private final List<ObjectNode> lineItems = new ArrayList<>();
        private final List<ObjectNode> failedOrders = new ArrayList<>();
        private final Map<String, ObjectNode> chargedBySku = new LinkedHashMap<>();
        private final Map<String, ObjectNode> failedBySku = new LinkedHashMap<>();
    }
}
