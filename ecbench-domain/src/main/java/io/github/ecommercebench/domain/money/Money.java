package io.github.ecommercebench.domain.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。
 *
 * <p>所有金额在进入领域模型时统一保留两位小数并采用四舍五入。该类型允许负数，用于表达银行账户透支； 是否破产由仿真层根据连续负余额天数判断。
 */
public record Money(@JsonValue BigDecimal amount) implements Comparable<Money> {

  public static final int SCALE = 2;
  public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
  public static final Money ZERO = new Money(BigDecimal.ZERO);

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public Money {
    Objects.requireNonNull(amount, "amount 不能为空");
    amount = amount.setScale(SCALE, ROUNDING_MODE);
  }

  public static Money of(String value) {
    return new Money(new BigDecimal(value));
  }

  public static Money of(double value) {
    return new Money(BigDecimal.valueOf(value));
  }

  public Money add(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return new Money(amount.add(other.amount));
  }

  public Money subtract(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return new Money(amount.subtract(other.amount));
  }

  public Money multiply(BigDecimal multiplier) {
    Objects.requireNonNull(multiplier, "multiplier 不能为空");
    return new Money(amount.multiply(multiplier));
  }

  public Money negate() {
    return new Money(amount.negate());
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  @Override
  public int compareTo(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return amount.compareTo(other.amount);
  }

  @Override
  public String toString() {
    return amount.toPlainString();
  }
}
