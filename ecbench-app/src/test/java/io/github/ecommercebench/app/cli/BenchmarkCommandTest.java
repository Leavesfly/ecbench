package io.github.ecommercebench.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * CLI 参数契约测试：与 Python `run.py` 兼容的空格分隔参数、默认值、以及 --model 必填。
 */
class BenchmarkCommandTest {

    private static CliRunOptions parse(String... args) {
        CliRunOptions options = new CliRunOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    private static int exitCode(String... args) {
        return new CommandLine(new BenchmarkCommand()).execute(args);
    }

    @Test
    void parsesPythonCompatibleSpaceSeparatedArguments() {
        CliRunOptions o =
                parse(
                        "--model", "gpt",
                        "--max-days", "10",
                        "--runs", "3",
                        "--max-tokens", "2048",
                        "--initial-balance", "500.5",
                        "--seed", "7");

        assertThat(o.model()).isEqualTo("gpt");
        assertThat(o.maxDays()).isEqualTo(10);
        assertThat(o.runs()).isEqualTo(3);
        assertThat(o.maxTokens()).isEqualTo(2048);
        assertThat(o.initialBalance()).isEqualTo(500.5);
        assertThat(o.seed()).isEqualTo(7L);
    }

    @Test
    void appliesPythonDefaultsWhenOptionsOmitted() {
        CliRunOptions o = parse("--model", "gpt");

        assertThat(o.maxTokens()).isEqualTo(16384);
        assertThat(o.maxTurns()).isEqualTo(4000);
        assertThat(o.maxDays()).isEqualTo(365);
        assertThat(o.initialBalance()).isEqualTo(100000.0);
        assertThat(o.dailyFee()).isEqualTo(50.0);
        assertThat(o.maxTokenCapacity()).isEqualTo(128000);
        assertThat(o.runs()).isEqualTo(1);
        assertThat(o.seed()).isNull();
        assertThat(o.tokenizerPath()).isNull();
        assertThat(o.logDir()).isNull();
        assertThat(o.jobFile()).isNull();
        assertThat(o.dataDir()).isNull();
    }

    @Test
    void modelIsRequired() {
        assertThat(exitCode("--max-days", "1")).isNotZero();
    }
}
