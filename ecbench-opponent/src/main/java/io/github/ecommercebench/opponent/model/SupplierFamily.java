package io.github.ecommercebench.opponent.model;

/** TERMS Bench 使用的供应商谈判风格。 */
public enum SupplierFamily {
  EXPRESSIVE("Expressive"),
  CANDID("Candid"),
  STOCHASTIC("Stochastic"),
  TACITURN("Taciturn"),
  STRATEGIC("Strategic"),
  ADVERSARIAL("Adversarial");

  private final String displayName;

  SupplierFamily(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }

  public static SupplierFamily fromPersonality(String value) {
    if (value == null) {
      return CANDID;
    }
    return switch (value) {
      case "Friendly" -> CANDID;
      case "Professional" -> TACITURN;
      case "Enthusiastic" -> EXPRESSIVE;
      case "Strategic" -> STRATEGIC;
      case "Unpredictable" -> STOCHASTIC;
      case "Tough", "Adversarial" -> ADVERSARIAL;
      default -> CANDID;
    };
  }
}
