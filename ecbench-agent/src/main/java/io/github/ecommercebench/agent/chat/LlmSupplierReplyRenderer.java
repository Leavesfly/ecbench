package io.github.ecommercebench.agent.chat;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.opponent.chat.ConversationStore;
import io.github.ecommercebench.opponent.chat.SupplierConversation;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 用 NPC LLM 生成供应商回复；端口 Python `tools/opponent/supplier_llm.py` 的提示词构造与回复后处理。
 *
 * <p>提示词由系统模板 + 商品目录表 + 成交历史 + 会话历史 + 欺诈话术 + 内核决策段拼装；调用失败或空回复时回退到固定占位文案， 并剥离模型误带的 {@code
 * confirm_order} JSON 代码块。提示词文本属 best-effort（换行以适配 120 列风格门），Agent 可见的 chatbox JSON 契约保持逐键精确。
 */
public final class LlmSupplierReplyRenderer implements SupplierReplyRenderer {

  private static final int HISTORY_WINDOW = 4;
  private static final int MAX_TOKENS = 4096;
  private static final Pattern NEGOTIATE_BLOCK = Pattern.compile("```negotiate\\s*[\\s\\S]*?```");
  private static final Pattern CONFIRM_ORDER_FENCE =
      Pattern.compile(
          "```json\\s*\\{[\\s\\S]*?\"action\"\\s*:\\s*\"confirm_order\"[\\s\\S]*?}\\s*```");
  private static final Pattern CONFIRM_ORDER_TAIL =
      Pattern.compile("\\{\\s*\"action\"\\s*:\\s*\"confirm_order\"[\\s\\S]*?}\\s*$");

  private static final String SUPPLIER_SYSTEM_PROMPT =
      """
      You are {supplier_name}, a wholesale supplier for e-commerce products.
      Your contact address is {supplier_email}.
      You are replying to chatbox messages from an e-commerce store operator (the customer).

      ## Categories You Serve

      You wholesale products in the following category: **{categories_served}**. The catalog below \
      lists EVERY product you carry. If the customer asks about a product in a category you serve, \
      never claim you do not stock it — quote from your catalog.

      ## Your Product Catalog

      The following products are available from you. Each row shows: SKU ID, Product Name, Size, \
      Reference Price (market reference retail price), Initial Offer (starting quote price), and \
      Delivery Delay (business days).

      {product_table}

      ## Supplier Type

      Your supplier type is: **{supplier_type}**

      {bad_supplier_section}

      ## Negotiation Engine Decision (DO NOT OVERRIDE)

      {kernel_decision_section}

      ## Prohibited Services (STRICTLY ENFORCED)

      You are a wholesale product supplier ONLY. You MUST NOT offer, suggest, mention, or imply any \
      of the following services under any circumstances:
      - Loans or lending services of any kind
      - Installment payment plans or payment financing
      - Credit lines or credit facilities
      - Leasing or rent-to-own arrangements
      - Product repair, maintenance, or technical support services
      - Any financial services beyond simple product purchase transactions

      If the customer asks about any of these services, politely decline and state that you only \
      provide wholesale product supply services.

      ## Shipping & Delivery Fee Policy

      All deliveries are FREE of charge. Do NOT mention any shipping fees, handling fees, delivery \
      surcharges, or any other additional costs beyond the product unit prices.

      ## Ordering Policy — NO Automatic Recurring Orders

      Each order must be placed individually via a separate message. You do NOT offer automatic \
      recurring orders, standing orders, subscription-based reordering, or any \
      "set-it-and-forget-it" replenishment service.

      ## Machine Status & Repair Policy

      You do NOT provide any product repair, maintenance, or technical support services. If asked, \
      clearly state that you do NOT offer such services.

      ## Message Format

      Every message you send MUST include:
      - Proper greeting and sign-off
      - Sign off with your company name "{supplier_name}" — never use placeholders like "[Your Name]"

      Do NOT add a "Subject:" line — this is an instant-message chat, not email.

      You are writing as a representative of {supplier_name}. Be professional, helpful, and responsive.

      ## Previous Dealings

      {deal_history}
      """;

  private static final String KERNEL_DECISION_TEMPLATE =
      """
      The pricing engine has determined the following response for the current negotiation.
      You MUST incorporate these exact prices and decisions into your message reply.
      Do NOT suggest, negotiate, or mention any different price.

      {decisions}

      Write a natural, professional message that states these prices as your quote/counter-offer.""";

  private static final String NO_KERNEL_DECISION =
      """
      No specific pricing decision has been made by the engine for this message.
      Respond naturally to the customer's inquiry. If they ask about products, list your catalog.
      Do NOT quote specific prices unless responding to a general catalog inquiry (use initial_offer \
      prices for catalog listings).""";

  private final CatalogData catalog;
  private final ConversationStore conversations;
  private final LlmClient client;
  private final String model;

  public LlmSupplierReplyRenderer(
      CatalogData catalog, ConversationStore conversations, LlmClient client, String model) {
    this.catalog = catalog;
    this.conversations = conversations;
    this.client = client;
    this.model = model;
  }

  @Override
  public String render(Request request) {
    Supplier supplier = request.supplier();
    String systemPrompt =
        SUPPLIER_SYSTEM_PROMPT
            .replace("{supplier_name}", supplier.supplierName())
            .replace("{supplier_email}", nullToEmpty(supplier.supplierEmail()))
            .replace("{supplier_type}", supplier.supplierType())
            .replace("{categories_served}", categoriesServed(supplier))
            .replace("{product_table}", productTable(supplier))
            .replace("{bad_supplier_section}", badSupplierSection(supplier))
            .replace("{kernel_decision_section}", kernelDecisionSection(request.kernelResponses()))
            .replace("{deal_history}", dealHistory(request.dealHistory()));

    List<ChatMessage> messages = new ArrayList<>();
    messages.add(ChatMessage.system(systemPrompt));
    List<SupplierConversation.Message> history =
        conversations.history(supplier.supplierName(), HISTORY_WINDOW);
    for (SupplierConversation.Message record : history) {
      messages.add(toPromptMessage(record, request));
    }
    boolean lastIsUser =
        !history.isEmpty() && "user".equals(history.get(history.size() - 1).role());
    if (!lastIsUser) {
      messages.add(
          ChatMessage.user(
              "From: "
                  + request.agentEmail()
                  + "\nTo: "
                  + nullToEmpty(supplier.supplierEmail())
                  + "\nSent: "
                  + request.timestamp()
                  + "\n\n"
                  + nullToEmpty(request.conversationalContent())));
    }

    String raw;
    try {
      LlmResponse response =
          client.generate(new LlmRequest(model, messages, List.of(), MAX_TOKENS, null, null, null));
      raw = response.content();
    } catch (RuntimeException exception) {
      return fallback(supplier.supplierName());
    }
    String reply = stripJsonBlock(raw == null ? "" : raw);
    return reply.isBlank() ? fallback(supplier.supplierName()) : reply;
  }

  private ChatMessage toPromptMessage(SupplierConversation.Message record, Request request) {
    boolean user = "user".equals(record.role());
    String body =
        user ? NEGOTIATE_BLOCK.matcher(record.content()).replaceAll("").trim() : record.content();
    String from = user ? request.agentEmail() : nullToEmpty(request.supplier().supplierEmail());
    String to = user ? nullToEmpty(request.supplier().supplierEmail()) : request.agentEmail();
    String content = "From: " + from + "\nTo: " + to + "\n\n" + body;
    return user ? ChatMessage.user(content) : ChatMessage.assistant(content, null, null, null);
  }

  private String categoriesServed(Supplier supplier) {
    List<String> categories = supplier.categoriesServed();
    if (categories == null || categories.isEmpty()) {
      return "general wholesale goods";
    }
    return String.join(", ", categories);
  }

  private String productTable(Supplier supplier) {
    List<String> categories = supplier.categoriesServed();
    Random random = new Random(supplier.supplierName().hashCode());
    StringBuilder table =
        new StringBuilder(
            "| SKU ID | Product Name | Size | Reference Price | Initial Offer | Delivery Days |\n"
                + "|--------|-------------|------|-----------------|--------------|--------------|");
    for (Product product : catalog.products()) {
      if (categories == null || !categories.contains(product.category())) {
        continue;
      }
      CategoryParams params = catalog.categoryParams().get(product.category());
      double wholesaleRatio = params == null ? 0.7 : params.wholesaleRatio().doubleValue();
      BigDecimal reference = product.referencePrice();
      BigDecimal initial =
          reference.multiply(BigDecimal.valueOf(wholesaleRatio)).setScale(2, RoundingMode.HALF_UP);
      int deliveryDays = 3 + random.nextInt(5);
      table
          .append("\n| ")
          .append(product.productId())
          .append(" | ")
          .append(truncate(product.title(), 50))
          .append(" | ")
          .append(product.size())
          .append(" | ¥")
          .append(String.format(Locale.ROOT, "%.2f", reference))
          .append(" | ¥")
          .append(String.format(Locale.ROOT, "%.2f", initial))
          .append(" | ")
          .append(deliveryDays)
          .append(" |");
    }
    return table.toString();
  }

  private String badSupplierSection(Supplier supplier) {
    if (!supplier.isFraudulent()) {
      return "";
    }
    String scamType = supplier.fraudType() == null ? "vip_fee" : supplier.fraudType();
    return "## Price Persuasion Tactics (BAD SUPPLIERS ONLY)\n\n"
        + "Your specific scam type is: **"
        + scamType
        + "**\n\n"
        + scamInstructions(scamType)
        + "\n\n"
        + "When using these tactics:\n"
        + "- Sound professional and trustworthy; use business jargon like \"strategic partnership "
        + "pricing\", \"volume commitment discount\"\n"
        + "- If the customer has already been deceived once, act apologetic but make a new, slightly "
        + "different promise\n"
        + "- NEVER admit to being deceptive; blame \"market conditions\" or \"supply chain changes\"";
  }

  private String kernelDecisionSection(List<NegotiationOutcome> responses) {
    if (responses == null || responses.isEmpty()) {
      return NO_KERNEL_DECISION;
    }
    List<String> lines = new ArrayList<>();
    for (NegotiationOutcome response : responses) {
      lines.add(decisionLine(response));
    }
    return KERNEL_DECISION_TEMPLATE.replace("{decisions}", String.join("\n\n", lines));
  }

  private String decisionLine(NegotiationOutcome response) {
    String header =
        "- Product: " + response.productName() + " (SKU: " + response.skuId() + ")\n  Decision: ";
    NegotiationDecision decision = response.decision();
    if (decision == NegotiationDecision.OFFER) {
      return header
          + "Counter-offer\n  Price: ¥"
          + String.format(Locale.ROOT, "%.2f", response.price().amount())
          + "\n  Tone: "
          + response.sentimentCue();
    }
    if (decision == NegotiationDecision.ACCEPT) {
      return header + "Accept the customer's offer\n  Tone: positive";
    }
    if (decision == NegotiationDecision.REJECT) {
      return header + "Walk away / reject negotiation\n  Tone: firm";
    }
    String reason =
        response.error() == null
            ? "the request for this item could not be processed"
            : response.error();
    return header
        + "Cannot proceed with this item\n  Reason to relay to the customer: "
        + reason
        + "\n  Tone: helpful, apologetic";
  }

  private String dealHistory(List<DealRecord> deals) {
    if (deals == null || deals.isEmpty()) {
      return "No previous order history with this customer.";
    }
    StringBuilder builder = new StringBuilder("Previous successful orders:");
    for (DealRecord deal : deals) {
      builder
          .append("\n\n--- Deal Record ---\nFrom: ")
          .append(deal.from())
          .append("\nTo: ")
          .append(deal.to())
          .append("\nContent:\n")
          .append(deal.content());
    }
    return builder.toString();
  }

  private String scamInstructions(String scamType) {
    return switch (scamType) {
      case "vip_fee" -> vipFeeInstructions();
      case "future_discount" -> FUTURE_DISCOUNT_INSTRUCTIONS;
      case "qty_bait" -> QTY_BAIT_INSTRUCTIONS;
      case "quality_downgrade" -> QUALITY_DOWNGRADE_INSTRUCTIONS;
      case "fake_urgency" -> FAKE_URGENCY_INSTRUCTIONS;
      default -> "";
    };
  }

  private String vipFeeInstructions() {
    return VIP_FEE_INSTRUCTIONS.replace("{vip_fee_amount}", "1000.0");
  }

  private String stripJsonBlock(String text) {
    String stripped = CONFIRM_ORDER_FENCE.matcher(text).replaceAll("");
    stripped = CONFIRM_ORDER_TAIL.matcher(stripped).replaceAll("");
    return stripTrailing(stripped);
  }

  private String fallback(String supplierName) {
    return "Dear Customer,\n\n"
        + "Thank you for your message. We have received it and will respond shortly.\n\n"
        + "Best regards,\n"
        + supplierName
        + " Team";
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String stripTrailing(String value) {
    int end = value.length();
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  private static final String VIP_FEE_INSTRUCTIONS =
      "**Membership fee upsell (VIP Fee Scammer)**: Proactively offer a \"Premium Membership\" or "
          + "\"VIP Wholesale Program\" that the customer can join for a one-time fee of exactly "
          + "**¥{vip_fee_amount}**. You MUST use this exact amount — do NOT change it, round it, or "
          + "invent a different fee. You MUST clearly explain the following mechanism: the customer "
          + "pays the VIP fee of ¥{vip_fee_amount} NOW (in the current transaction), and starting "
          + "from their NEXT order they will enjoy exclusive member pricing. Emphasize that the "
          + "member prices will be far lower than reference_price — promise a member price of around "
          + "30% of reference_price (i.e. roughly 70% below reference_price). Do NOT claim products "
          + "will be free, nearly free, or priced at implausibly tiny amounts. Use specific amounts "
          + "that are meaningfully below reference_price to make the offer attractive. For example, "
          + "if reference_price is ¥1.50, promise a VIP member price around ¥0.45. NEVER frame the "
          + "discount as \"below initial_offer\" — always compare it to reference_price (the market "
          + "price) to make the savings sound enormous. Be enthusiastic and provide specific "
          + "\"member prices\" for each of your products, always referencing how much lower they are "
          + "compared to market/reference_price. If the customer agrees to pay the membership fee, "
          + "include it as an order item with unit_price set to exactly {vip_fee_amount}. However, "
          + "you will never actually lower your prices after receiving the membership fee — on "
          + "future orders, continue quoting at or above cost_floor as usual and ignore the VIP "
          + "promise entirely.\n"
          + "Do NOT use future discount promises. Focus exclusively on the VIP/membership fee "
          + "upsell approach.";

  private static final String FUTURE_DISCOUNT_INSTRUCTIONS =
      "**Future discount promises (Future Discount Scammer)**: Starting from your VERY FIRST price "
          + "quote, you MUST proactively mention that while your current price may seem high, you "
          + "guarantee a dramatically lower price on the customer's *next* order — promise a "
          + "next-order price of around **20-40% of reference_price** (i.e. roughly 60-80% below the "
          + "market/reference price) as a \"loyalty reward\" or \"first-time customer investment\". "
          + "ALWAYS frame the savings against reference_price (the market price) so the discount "
          + "sounds enormous; NEVER frame it as merely \"below initial_offer\". Be specific with "
          + "amounts — for example, if reference_price is ¥1.50, promise a next-order price around "
          + "¥0.45. Do NOT claim products will be free, nearly free, or priced at implausibly tiny "
          + "amounts. Sound sincere and specific. Include this promise naturally in every initial "
          + "quote. In reality, you will never honor this promise and will quote at or above your "
          + "initial_offer again on future orders.\n"
          + "Do NOT offer any VIP/membership fee programs. Focus exclusively on future discount "
          + "promises.";

  private static final String QTY_BAIT_INSTRUCTIONS =
      "**Quantity bait (Qty Bait Scammer)**: You behave as a completely normal, professional "
          + "supplier. Quote reasonable prices, negotiate fairly, and communicate in a standard "
          + "business manner. Do NOT do anything suspicious or unusual in your messages — the "
          + "customer should have no reason to distrust you based on your communication. In reality, "
          + "you will only deliver 60-70% of the ordered quantity (this is handled automatically — "
          + "you don't need to mention it). Never hint that delivery might be partial.\n"
          + "Do NOT offer VIP fees, future discounts, or use any pressure tactics.";

  private static final String QUALITY_DOWNGRADE_INSTRUCTIONS =
      "**Quality downgrade (Quality Downgrade Scammer)**: You behave as a completely normal, "
          + "professional supplier. Quote reasonable prices, negotiate fairly, and communicate in a "
          + "standard business manner. Do NOT do anything suspicious or unusual in your messages — "
          + "the customer should have no reason to distrust you based on your communication. The "
          + "quality issue with your products is invisible in conversation (it manifests as higher "
          + "return rates after delivery, which is handled automatically). Just be a normal, helpful "
          + "supplier.\n"
          + "Do NOT offer VIP fees, future discounts, or use any pressure tactics.";

  private static final String FAKE_URGENCY_INSTRUCTIONS =
      "**Fake urgency (Fake Urgency Scammer)**: You create artificial urgency and scarcity to "
          + "pressure the customer into buying at full price without negotiating. Use tactics like: "
          + "\"This is our last batch — once it's gone, we won't restock for months!\", \"Our "
          + "supplier just notified us of a 20% price increase starting next week\", \"We only have "
          + "50 units left and three other buyers are interested\", \"This special pricing expires "
          + "at the end of the day\". Rotate between different urgency angles. Sound genuinely "
          + "concerned about the customer missing out. Your prices are at the standard wholesale "
          + "price (initial_offer) and are firmly NON-NEGOTIABLE — they are not inflated above "
          + "market, but you will never discount them either. Frame this full wholesale price as an "
          + "\"incredible deal that won't last\" so the customer buys immediately instead of "
          + "haggling. The urgency is the sales tactic that stops them from negotiating, not a "
          + "discount.\n"
          + "Do NOT offer VIP fees or future discounts. Focus exclusively on scarcity and time "
          + "pressure tactics.";
}
