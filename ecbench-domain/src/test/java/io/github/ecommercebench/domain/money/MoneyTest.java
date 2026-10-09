package io.github.ecommercebench.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void creationRoundsToTwoDecimalsUsingHalfUp() {
        Money result = Money.of("10.005");

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("10.01"));
    }

    @Test
    void additionKeepsTwoDecimalScale() {
        Money result = Money.of("10.01").add(Money.of("0.02"));

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("10.03"));
    }

    @Test
    void multiplicationRoundsToTwoDecimalsUsingHalfUp() {
        Money result = Money.of("100.00").multiply(new BigDecimal("0.3333"));

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("33.33"));
    }

    @Test
    void subtractionMayProduceNegativeBalance() {
        Money result = Money.of("50").subtract(Money.of("120"));

        assertThat(result).isEqualTo(Money.of("-70"));
    }

    @Test
    void equalityIgnoresInputScaleAfterNormalization() {
        assertThat(Money.of("10")).isEqualTo(Money.of("10.00"));
    }

    @Test
    void serializesAsPlainJsonNumber() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(Money.of("10"))).isEqualTo("10.00");
        assertThat(mapper.readValue("10.005", Money.class)).isEqualTo(Money.of("10.01"));
    }
}
