package io.github.ecommercebench.opponent.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import org.junit.jupiter.api.Test;

class NegotiationBlockParserTest {

    private final NegotiationBlockParser parser = new NegotiationBlockParser(new ObjectMapper());

    @Test
    void parsesObjectArrayAndNdjsonWhileKeepingConversationText() {
        String content =
                "Please consider this.\n```negotiate\n"
                        + "{\"action\":\"offer\",\"sku_id\":\"SKU-1\",\"price\":15.5,\"quantity\":50}\n"
                        + "{\"action\":\"reject\",\"sku_id\":\"SKU-2\"}\n```\nThanks";

        ParsedNegotiation parsed = parser.parse(content);

        assertThat(parsed.actions())
                .containsExactly(
                        new NegotiationAction.Offer("sku-1", Money.of("15.5"), 50),
                        new NegotiationAction.Reject("sku-2", 1));
        assertThat(parsed.conversationalText()).isEqualTo("Please consider this.\n\nThanks");
    }

    @Test
    void acceptAllowsMissingPriceAndDefaultsQuantityToOne() {
        ParsedNegotiation parsed =
                parser.parse("```negotiate\n{\"action\":\"accept\",\"sku_id\":\"SKU-1\"}\n```");

        assertThat(parsed.actions())
                .containsExactly(new NegotiationAction.Accept("sku-1", null, 1, null));
    }

    @Test
    void malformedAndUnsupportedActionsAreIgnored() {
        ParsedNegotiation parsed =
                parser.parse("```negotiate\n{bad}\n{\"action\":\"buy\",\"sku_id\":\"sku\"}\n```");

        assertThat(parsed.actions()).isEmpty();
    }
}
