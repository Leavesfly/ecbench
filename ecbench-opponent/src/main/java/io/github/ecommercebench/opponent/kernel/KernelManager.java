package io.github.ecommercebench.opponent.kernel;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.model.CounterpartAction;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import io.github.ecommercebench.opponent.model.NegotiationState;
import io.github.ecommercebench.opponent.model.SupplierFamily;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理每个 supplier/SKU 独立的谈判内核和协议提交状态。 */
public final class KernelManager {

  private final CatalogData catalog;
  private final SupplierPolicy policy;
  private final RandomStreams randomStreams;
  private final NegotiationTracker tracker;
  private final Map<String, CounterpartKernel> kernels = new LinkedHashMap<>();
  private final Map<String, Integer> rounds = new LinkedHashMap<>();
  private final Map<String, NegotiationState> states = new LinkedHashMap<>();
  private final Map<String, Money> lastPrices = new LinkedHashMap<>();
  private final Map<String, PendingAgreement> pendingAgreements = new LinkedHashMap<>();
  private final Map<String, Integer> cycles = new LinkedHashMap<>();
  private final Map<String, Supplier> suppliersByName = new LinkedHashMap<>();
  private final Map<String, Product> productsById = new LinkedHashMap<>();

  public KernelManager(
      CatalogData catalog,
      SupplierPolicy policy,
      RandomStreams randomStreams,
      NegotiationTracker tracker) {
    this.catalog = catalog;
    this.policy = policy;
    this.randomStreams = randomStreams;
    this.tracker = tracker;
    catalog.suppliers().forEach(supplier -> suppliersByName.put(supplier.supplierName(), supplier));
    catalog.products().forEach(product -> productsById.put(product.productId(), product));
  }

  public NegotiationOutcome processAction(
      String supplierName, NegotiationAction action, int currentDay) {
    String key = key(supplierName, action.skuId());
    Supplier supplier = suppliersByName.get(supplierName);
    Product product = productsById.get(action.skuId());
    if (supplier == null
        || product == null
        || !supplier.categoriesServed().contains(product.category())) {
      return NegotiationOutcome.error(
          action.skuId(),
          "unknown_sku",
          "Unknown SKU " + action.skuId() + " for supplier " + supplierName + ".",
          rounds.getOrDefault(key, 0));
    }

    if (action instanceof NegotiationAction.Accept accept) {
      return accept(supplierName, product, accept, currentDay);
    }
    if (action instanceof NegotiationAction.Reject) {
      return reject(supplierName, product, currentDay);
    }
    NegotiationAction.Offer offer = (NegotiationAction.Offer) action;
    return offer(supplier, product, offer, currentDay);
  }

  private NegotiationOutcome offer(
      Supplier supplier, Product product, NegotiationAction.Offer offer, int day) {
    String key = key(supplier.supplierName(), product.productId());
    CounterpartKernel kernel = getOrCreateKernel(supplier, product, day);
    int round = rounds.getOrDefault(key, 1);
    tracker.recordAgentOffer(supplier.supplierName(), product.productId(), offer.price());
    CounterpartAction response = kernel.getAction(round, offer.price());
    rounds.put(key, round + 1);
    if (response.decision() == NegotiationDecision.ACCEPT) {
      states.put(key, NegotiationState.PENDING_ORDER);
      lastPrices.put(key, offer.price());
      pendingAgreements.put(key, new PendingAgreement(offer.price(), "SupplierAccept"));
      return outcome(product, response, round, offer.price(), true);
    }
    if (response.decision() == NegotiationDecision.REJECT) {
      states.put(key, NegotiationState.REJECTED);
      tracker.recordOutcome(
          supplier.supplierName(),
          product.productId(),
          "Disagreement",
          null,
          "SupplierReject",
          day);
      return outcome(product, response, round, null, false);
    }
    lastPrices.put(key, response.price());
    tracker.recordSupplierOffer(supplier.supplierName(), product.productId(), response.price());
    return outcome(product, response, round, null, false);
  }

  private NegotiationOutcome accept(
      String supplierName, Product product, NegotiationAction.Accept accept, int day) {
    String key = key(supplierName, product.productId());
    Money last = lastPrices.get(key);
    if (accept.price() == null) {
      return NegotiationOutcome.error(
          product.productId(),
          "accept_missing_price",
          "Accept requires an explicit price.",
          rounds.getOrDefault(key, 0));
    }
    if (states.get(key) != NegotiationState.ACTIVE || last == null) {
      return NegotiationOutcome.error(
          product.productId(),
          "no_active_negotiation",
          "No active negotiation for this SKU.",
          rounds.getOrDefault(key, 0));
    }
    if (accept.price().subtract(last).amount().abs().compareTo(new BigDecimal("0.005")) > 0) {
      return NegotiationOutcome.error(
          product.productId(),
          "accept_price_mismatch",
          "Accept price does not match the supplier's last offer.",
          rounds.get(key));
    }
    states.put(key, NegotiationState.PENDING_ORDER);
    pendingAgreements.put(key, new PendingAgreement(last, "AgentAccept"));
    return new NegotiationOutcome(
        product.productId(),
        product.title(),
        NegotiationDecision.ACCEPT,
        last,
        last,
        rounds.getOrDefault(key, 0),
        "positive",
        "Concede",
        null,
        null,
        true);
  }

  private NegotiationOutcome reject(String supplierName, Product product, int day) {
    String key = key(supplierName, product.productId());
    if (states.get(key) != NegotiationState.ACTIVE) {
      return NegotiationOutcome.error(
          product.productId(),
          "no_active_negotiation",
          "No active negotiation for this SKU.",
          rounds.getOrDefault(key, 0));
    }
    states.put(key, NegotiationState.REJECTED);
    tracker.recordOutcome(
        supplierName, product.productId(), "Disagreement", null, "AgentReject", day);
    return new NegotiationOutcome(
        product.productId(),
        product.title(),
        NegotiationDecision.REJECT,
        null,
        null,
        rounds.getOrDefault(key, 0),
        "negative",
        "Pressure",
        null,
        null,
        false);
  }

  private CounterpartKernel getOrCreateKernel(Supplier supplier, Product product, int day) {
    String key = key(supplier.supplierName(), product.productId());
    NegotiationState state = states.get(key);
    if (state == null
        || state == NegotiationState.COMPLETED
        || state == NegotiationState.REJECTED) {
      if (state != null) {
        cycles.merge(key, 1, Integer::sum);
      }
      CategoryParams params = catalog.categoryParams().get(product.category());
      Money floor = policy.computeEffectiveFloor(product, supplier, params);
      Money initial = new Money(product.referencePrice().multiply(params.wholesaleRatio()));
      SupplierFamily family = SupplierFamily.fromPersonality(supplier.personality());
      ParamPair supplierParams = parameters(supplier, family);
      Money maximum = initial.multiply(new BigDecimal("1.5"));
      double d0 =
          calibrateOpening(
              floor, maximum, supplierParams.kappa(), supplierParams.stance(), initial);
      CounterpartKernel kernel =
          new CounterpartKernel(
              new KernelParameters(
                  family,
                  floor,
                  supplierParams.kappa(),
                  supplierParams.stance(),
                  d0,
                  SupplierPolicy.MAX_ROUNDS,
                  supplier.isFraudulent() ? floor : Money.ZERO,
                  maximum),
              randomStreams.stream("negotiation:" + key + ":" + cycles.getOrDefault(key, 0)));
      kernels.put(key, kernel);
      rounds.put(key, 2);
      states.put(key, NegotiationState.ACTIVE);
      tracker.getOrCreate(
          supplier.supplierName(),
          product.productId(),
          supplier.supplierType(),
          family,
          new Money(product.referencePrice()),
          new Money(product.referencePrice().multiply(params.costFloorRatio())),
          initial,
          day);
      CounterpartAction opening = kernel.getAction(1, null);
      lastPrices.put(key, opening.price());
      tracker.recordSupplierOffer(supplier.supplierName(), product.productId(), opening.price());
    }
    return kernels.get(key);
  }

  public void commitAgreement(String supplierName, String skuId, int day) {
    String key = key(supplierName, skuId);
    PendingAgreement pending = pendingAgreements.remove(key);
    if (pending != null) {
      tracker.recordOutcome(
          supplierName, skuId, "Agreement", pending.price(), pending.actor(), day);
      states.put(key, NegotiationState.COMPLETED);
    }
  }

  public void rollbackAgreement(String supplierName, String skuId) {
    String key = key(supplierName, skuId);
    pendingAgreements.remove(key);
    states.put(key, NegotiationState.ACTIVE);
  }

  public Money lastOffer(String supplierName, String skuId) {
    return lastPrices.get(key(supplierName, skuId));
  }

  public NegotiationState state(String supplierName, String skuId) {
    return states.get(key(supplierName, skuId));
  }

  public NegotiationTracker tracker() {
    return tracker;
  }

  private NegotiationOutcome outcome(
      Product product, CounterpartAction action, int round, Money agreedPrice, boolean pending) {
    return new NegotiationOutcome(
        product.productId(),
        product.title(),
        action.decision(),
        action.price(),
        agreedPrice,
        round,
        action.sentimentCue(),
        action.strategicCue(),
        null,
        null,
        pending);
  }

  private ParamPair parameters(Supplier supplier, SupplierFamily family) {
    if (supplier.isFraudulent()) {
      return switch (FraudType.fromWireName(supplier.fraudType())) {
        case VIP_FEE -> new ParamPair(0.15, "aggressive");
        case FUTURE_DISCOUNT, FAKE_URGENCY -> new ParamPair(0.20, "aggressive");
        case QTY_BAIT, QUALITY_DOWNGRADE -> new ParamPair(0.35, "neutral");
        default -> new ParamPair(0.20, "aggressive");
      };
    }
    return switch (family) {
      case EXPRESSIVE -> new ParamPair(0.70, "conciliatory");
      case CANDID -> new ParamPair(0.65, "neutral");
      case STOCHASTIC -> new ParamPair(0.60, "neutral");
      case TACITURN -> new ParamPair(0.60, "aggressive");
      case STRATEGIC -> new ParamPair(0.55, "aggressive");
      case ADVERSARIAL -> new ParamPair(0.50, "aggressive");
    };
  }

  private double calibrateOpening(
      Money reservation, Money maximum, double kappa, String stance, Money target) {
    double phi =
        Math.max(
            0.5,
            Math.min(
                1.5,
                1.0
                    - 0.3 * kappa
                    + ("aggressive".equals(stance) ? 0.15 : 0.0)
                    - ("conciliatory".equals(stance) ? 0.15 : 0.0)));
    double denominator = phi * maximum.subtract(reservation).amount().doubleValue();
    if (denominator < 1e-9) {
      return 0.7;
    }
    double d0 = target.subtract(reservation).amount().doubleValue() / denominator;
    return Math.max(0.0, Math.min(0.99, d0));
  }

  private static String key(String supplierName, String skuId) {
    return supplierName + "\u0000" + skuId;
  }

  private record PendingAgreement(Money price, String actor) {}

  private record ParamPair(double kappa, String stance) {}
}
