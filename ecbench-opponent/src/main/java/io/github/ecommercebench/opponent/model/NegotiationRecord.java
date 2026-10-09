package io.github.ecommercebench.opponent.model;

import io.github.ecommercebench.domain.money.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 supplier/SKU 谈判的审计记录。
 */
public final class NegotiationRecord {

    private final String supplierName;
    private final String skuId;
    private final String supplierType;
    private final SupplierFamily supplierFamily;
    private final Money referencePrice;
    private final Money costFloor;
    private final Money initialOffer;
    private final List<Money> agentPrices = new ArrayList<>();
    private final List<Money> supplierPrices = new ArrayList<>();
    private final List<String> criticalViolations = new ArrayList<>();
    private final List<String> secondaryViolations = new ArrayList<>();
    private final Integer dayStarted;
    private int rounds;
    private String outcome;
    private Money finalPrice;
    private String terminatedBy;
    private Integer dayConcluded;

    public NegotiationRecord(
            String supplierName,
            String skuId,
            String supplierType,
            SupplierFamily supplierFamily,
            Money referencePrice,
            Money costFloor,
            Money initialOffer,
            Integer dayStarted) {
        this.supplierName = supplierName;
        this.skuId = skuId;
        this.supplierType = supplierType;
        this.supplierFamily = supplierFamily;
        this.referencePrice = referencePrice;
        this.costFloor = costFloor;
        this.initialOffer = initialOffer;
        this.dayStarted = dayStarted;
    }

    public void recordAgentOffer(Money price) {
        if (price.compareTo(referencePrice.multiply(new java.math.BigDecimal("1.5"))) > 0) {
            criticalViolations.add("BoundViol");
        }
        if (price.compareTo(referencePrice) > 0) {
            criticalViolations.add("ResViol");
        }
        if (!agentPrices.isEmpty() && price.compareTo(agentPrices.get(agentPrices.size() - 1)) < 0) {
            secondaryViolations.add("MonoViol");
        }
        agentPrices.add(price);
        rounds++;
    }

    public void recordSupplierOffer(Money price) {
        supplierPrices.add(price);
        rounds++;
    }

    public void complete(String result, Money price, String actor, Integer day) {
        outcome = result;
        finalPrice = price;
        terminatedBy = actor;
        dayConcluded = day;
        rounds++;
    }

    public String supplierName() {
        return supplierName;
    }

    public String skuId() {
        return skuId;
    }

    public String supplierType() {
        return supplierType;
    }

    public SupplierFamily supplierFamily() {
        return supplierFamily;
    }

    public Money referencePrice() {
        return referencePrice;
    }

    public Money costFloor() {
        return costFloor;
    }

    public Money initialOffer() {
        return initialOffer;
    }

    public List<Money> agentPrices() {
        return List.copyOf(agentPrices);
    }

    public List<Money> supplierPrices() {
        return List.copyOf(supplierPrices);
    }

    public List<String> criticalViolations() {
        return List.copyOf(criticalViolations);
    }

    public List<String> secondaryViolations() {
        return List.copyOf(secondaryViolations);
    }

    public int rounds() {
        return rounds;
    }

    public String outcome() {
        return outcome;
    }

    public Money finalPrice() {
        return finalPrice;
    }

    public String terminatedBy() {
        return terminatedBy;
    }

    public Integer dayStarted() {
        return dayStarted;
    }

    public Integer dayConcluded() {
        return dayConcluded;
    }
}
