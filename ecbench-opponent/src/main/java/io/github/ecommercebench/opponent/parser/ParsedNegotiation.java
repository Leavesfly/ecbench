package io.github.ecommercebench.opponent.parser;

import io.github.ecommercebench.opponent.model.NegotiationAction;
import java.util.List;

/** negotiate 动作与剥离代码块后的普通对话文本。 */
public record ParsedNegotiation(List<NegotiationAction> actions, String conversationalText) {
  public ParsedNegotiation {
    actions = List.copyOf(actions);
  }
}
