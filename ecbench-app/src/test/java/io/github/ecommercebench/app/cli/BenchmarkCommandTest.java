package io.github.ecommercebench.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.web.DashboardLauncher;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * CLI 参数契约测试：与 Python `run.py` 兼容的空格分隔参数、默认值，以及「无 --model 时改为起 Web 服务」的新语义。
 */
class BenchmarkCommandTest {

    private static CliRunOptions parse(String... args) {
        CliRunOptions options = new CliRunOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    /**
     * 记录参数并立即返回 0，绝不真绑端口或阻塞。
     *
     * <p>只能覆盖 public 的 {@code launch}：{@code DashboardLauncher.start} 是包级方法，而本测试在 {@code app.cli}
     * 包，跨包子类无法覆盖它（写 {@code @Override} 会编译失败）。
     */
    private static final class RecordingLauncher extends DashboardLauncher {
        private int calls;
        private String host;
        private int port;
        private String catalog;
        private RunOptions defaults;
        private String logDirOverride;

        @Override
        public int launch(
                String host,
                int port,
                String catalog,
                RunOptions serverDefaults,
                Path workingDir,
                String logDirOverride) {
            this.calls++;
            this.host = host;
            this.port = port;
            this.catalog = catalog;
            this.defaults = serverDefaults;
            this.logDirOverride = logDirOverride;
            return 0;
        }
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

    /* ---------------------------------- 新语义：无 --model → 起 Web 服务 */

    @Test
    void startsDashboardWhenModelIsAbsent() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--port", "0");

        assertThat(exit).isZero();
        assertThat(launcher.calls).isEqualTo(1);
        assertThat(launcher.host).isEqualTo("127.0.0.1");
        assertThat(launcher.port).isZero();
        assertThat(launcher.logDirOverride).isNull();
    }

    @Test
    void blankModelAlsoStartsDashboard() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--model", "   ");

        assertThat(exit).isZero();
        assertThat(launcher.calls).isEqualTo(1);
    }

    @Test
    void bareInvocationUsesTheDocumentedDefaults() {
        RecordingLauncher launcher = new RecordingLauncher();

        new CommandLine(new BenchmarkCommand(null, launcher)).execute();

        assertThat(launcher.calls).isEqualTo(1);
        assertThat(launcher.host).isEqualTo("127.0.0.1");
        assertThat(launcher.port).isEqualTo(8080);
        assertThat(launcher.catalog).isNull();
    }

    @Test
    void serveOptionsReachTheLauncher() {
        RecordingLauncher launcher = new RecordingLauncher();

        new CommandLine(new BenchmarkCommand(null, launcher))
                .execute("--host", "0.0.0.0", "--port", "9999", "--catalog", "r.csv", "--log-dir", "custom-log");

        assertThat(launcher.host).isEqualTo("0.0.0.0");
        assertThat(launcher.port).isEqualTo(9999);
        assertThat(launcher.catalog).isEqualTo("r.csv");
        assertThat(launcher.logDirOverride).isEqualTo("custom-log");
        assertThat(launcher.defaults.logDir()).isEqualTo("custom-log");
    }

    /* ---------------------------------- 批处理路径必须逐字不变 */

    @Test
    void batchPathIsUnchangedAndStillNeedsAWiredFactory() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--model", "gpt");

        // 工厂未装配 → 2，与改动前一致；关键是 launcher 一次都没被调用，证明批处理没被误改成 serve。
        assertThat(exit).isEqualTo(2);
        assertThat(launcher.calls).isZero();
    }

    @Test
    void noArgConstructorStillParsesAndServeOptionsAreIndependent() {
        // Picocli 反射实例化路径（EcommerceBenchApplication 之外的直接 new）不得抛异常。
        BenchmarkCommand command = new BenchmarkCommand();

        assertThat(new CommandLine(command).execute("--help")).isZero();
        assertThat(command.options()).isNotNull();
    }
}
