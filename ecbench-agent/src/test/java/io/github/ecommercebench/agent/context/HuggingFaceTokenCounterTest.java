package io.github.ecommercebench.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class HuggingFaceTokenCounterTest {

    @Test
    void loadsRepositoryTokenizerAndCountsText() {
        Path tokenizer =
                Path.of(System.getProperty("user.dir"), "..", "tokenizer", "tokenizer.json").normalize();

        try (HuggingFaceTokenCounter counter = HuggingFaceTokenCounter.load(tokenizer)) {
            assertThat(counter.count("You are running an e-commerce business.")).isPositive();
        }
    }
}
