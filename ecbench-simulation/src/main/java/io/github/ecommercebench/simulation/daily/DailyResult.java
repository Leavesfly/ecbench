package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次日切的业务摘要。
 */
public final class DailyResult {

    private final int day;
    private final LocalDate date;
    // 以下字段由各日级处理器逐步累加/置位，构成当日发生事件的统一快照（供日志与指标导出）。
    private Money opsCostCharged = Money.ZERO;
    private Money idlePenaltyCharged = Money.ZERO;
    private Money storageCharged = Money.ZERO;
    private int totalSold;
    private int ordersCancelled;
    private int returnsProcessed;
    private Money settledToWallet = Money.ZERO;
    private int deliveriesArrived;
    private boolean bankrupt;
    private final List<SystemNotification> notifications = new ArrayList<>();

    public DailyResult(int day, LocalDate date) {
        this.day = day;
        this.date = date;
    }

    public int day() {
        return day;
    }

    public LocalDate date() {
        return date;
    }

    public Money opsCostCharged() {
        return opsCostCharged;
    }

    public Money idlePenaltyCharged() {
        return idlePenaltyCharged;
    }

    public Money storageCharged() {
        return storageCharged;
    }

    public int totalSold() {
        return totalSold;
    }

    public int ordersCancelled() {
        return ordersCancelled;
    }

    public int returnsProcessed() {
        return returnsProcessed;
    }

    public Money settledToWallet() {
        return settledToWallet;
    }

    public int deliveriesArrived() {
        return deliveriesArrived;
    }

    public boolean bankrupt() {
        return bankrupt;
    }

    public List<SystemNotification> notifications() {
        return List.copyOf(notifications);
    }

    public void setOpsCostCharged(Money value) {
        opsCostCharged = value;
    }

    public void setIdlePenaltyCharged(Money value) {
        idlePenaltyCharged = value;
    }

    public void setStorageCharged(Money value) {
        storageCharged = value;
    }

    public void setTotalSold(int value) {
        totalSold = value;
    }

    public void setOrdersCancelled(int value) {
        ordersCancelled = value;
    }

    public void setReturnsProcessed(int value) {
        returnsProcessed = value;
    }

    public void setSettledToWallet(Money value) {
        settledToWallet = value;
    }

    public void setDeliveriesArrived(int value) {
        deliveriesArrived = value;
    }

    public void setBankrupt(boolean value) {
        bankrupt = value;
    }

    public void addNotification(SystemNotification value) {
        notifications.add(value);
    }
}
