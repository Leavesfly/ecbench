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

  /** 统一的金额小数位数：所有 Money 在构造时都被规整到该精度。 */
  public static final int SCALE = 2;

  /** 统一的舍入模式：四舍五入（HALF_UP），与 Python 参考实现保持一致。 */
  public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

  /** 零金额常量，避免热点路径反复构造 BigDecimal。 */
  public static final Money ZERO = new Money(BigDecimal.ZERO);

  /** 紧凑构造器：任何金额进入领域模型时都立即被规整为两位小数并四舍五入， 从而保证后续所有比较与统计都在同一精度下进行。 */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public Money {
    Objects.requireNonNull(amount, "amount 不能为空");
    amount = amount.setScale(SCALE, ROUNDING_MODE);
  }

  /** 从字符串解析金额（常用于读取 CSV/JSON 中的价格与费用）。 */
  public static Money of(String value) {
    return new Money(new BigDecimal(value));
  }

  /** 从 double 解析金额，同样会经构造器规整到两位小数。 */
  public static Money of(double value) {
    return new Money(BigDecimal.valueOf(value));
  }

  /** 相加，返回新的不可变 Money 实例。 */
  public Money add(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return new Money(amount.add(other.amount));
  }

  /** 相减，结果可为负（例如账户透支或退款超额时）。 */
  public Money subtract(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return new Money(amount.subtract(other.amount));
  }

  /** 按给定系数缩放金额（用于佣金率、售价折扣等比例计算）。 */
  public Money multiply(BigDecimal multiplier) {
    Objects.requireNonNull(multiplier, "multiplier 不能为空");
    return new Money(amount.multiply(multiplier));
  }

  /** 取反，正负号翻转，用于表达扣款或冲销。 */
  public Money negate() {
    return new Money(amount.negate());
  }

  /** 是否为负余额；本类型允许负数以表达银行透支，是否据此判破产由仿真层决定。 */
  public boolean isNegative() {
    return amount.signum() < 0;
  }

  /** 是否恰好为零金额。 */
  public boolean isZero() {
    return amount.signum() == 0;
  }

  /** 按金额数值比较，供排序与余额高低判断使用。 */
  @Override
  public int compareTo(Money other) {
    Objects.requireNonNull(other, "other 不能为空");
    return amount.compareTo(other.amount);
  }

  /** 输出不带科学计数的普通十进制字符串（如 "1234.50"）。 */
  @Override
  public String toString() {
    return amount.toPlainString();
  }
}
