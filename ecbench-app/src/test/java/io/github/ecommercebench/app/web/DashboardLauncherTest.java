package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.config.RunOptions;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dashboard 启动编排的路径解析与前置校验。
 *
 * <p>路径解析的三条断言沿用原 {@code ServeCommandCatalogTest}，逐字未改——这是「serve 行为不变」的证据（该静态助手
 * 已从 {@code ServeCommand} 移到本类，以免两个命令入口各持一份）。
 */
class DashboardLauncherTest {

    private record Recorded(String host, int port, String catalog, RunOptions defaults, Path root, Path workingDir) {}

    /** 记录参数并立即返回 0，绝不真的绑定端口或阻塞。 */
    private static final class RecordingLauncher extends DashboardLauncher {
        private Recorded recorded;

        @Override
        int start(
                String host,
                int port,
                String catalog,
                RunOptions serverDefaults,
                Path root,
                Path workingDir) {
            this.recorded = new Recorded(host, port, catalog, serverDefaults, root, workingDir);
            return 0;
        }
    }

    /** 只提供 logDir 的最小 RunOptions。 */
    private record StubDefaults(String logDir) implements RunOptions {
        @Override
        public String model() {
            return null;
        }

        @Override
        public int maxTokens() {
            return 16384;
        }

        @Override
        public int maxTurns() {
            return 4000;
        }

        @Override
        public int maxDays() {
            return 365;
        }

        @Override
        public double initialBalance() {
            return 100000.0;
        }

        @Override
        public double dailyFee() {
            return 50.0;
        }

        @Override
        public int maxTokenCapacity() {
            return 128000;
        }

        @Override
        public String tokenizerPath() {
            return null;
        }

        @Override
        public String jobFile() {
            return null;
        }

        @Override
        public int runs() {
            return 1;
        }

        @Override
        public Long seed() {
            return null;
        }

        @Override
        public String dataDir() {
            return null;
        }
    }

    /* -------------------------------------------------- 路径解析（原 ServeCommandCatalogTest） */

    @Test
    void catalogDefaultsToDataDirUnderWorkingDir(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        Path catalog = DashboardLauncher.resolveCatalog(null, workingDir);

        assertThat(catalog).isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }

    @Test
    void explicitCatalogOverrideWins(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));
        Path custom = workingDir.resolve("elsewhere/roster.csv");

        assertThat(DashboardLauncher.resolveCatalog(custom.toString(), workingDir)).isEqualTo(custom);
    }

    @Test
    void blankCatalogOverrideIsIgnored(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        assertThat(DashboardLauncher.resolveCatalog("   ", workingDir))
                .isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }

    /* -------------------------------------------------- 前置校验与参数透传 */

    @Test
    void missingLogDirectoryYieldsExitCodeTwoAndNeverStarts(@TempDir Path workingDir) {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = launcher.launch("127.0.0.1", 0, null, new StubDefaults("absent"), workingDir, "absent");

        assertThat(exit).isEqualTo(2);
        assertThat(launcher.recorded).isNull();
    }

    @Test
    void existingLogDirectoryReachesStartWithAllParameters(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("log"));
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = launcher.launch("127.0.0.1", 0, "roster.csv", new StubDefaults("log"), workingDir, null);

        assertThat(exit).isZero();
        assertThat(launcher.recorded.port()).isZero();
        assertThat(launcher.recorded.host()).isEqualTo("127.0.0.1");
        assertThat(launcher.recorded.catalog()).isEqualTo("roster.csv");
        assertThat(launcher.recorded.defaults().logDir()).isEqualTo("log");
        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("log"));
        assertThat(launcher.recorded.workingDir()).isEqualTo(workingDir);
    }

    @Test
    void logDirOverrideIsResolvedAgainstWorkingDir(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("elsewhere"));
        RecordingLauncher launcher = new RecordingLauncher();

        launcher.launch("127.0.0.1", 0, null, new StubDefaults("elsewhere"), workingDir, "elsewhere");

        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("elsewhere"));
        assertThat(DashboardLauncher.resolveLogDir("elsewhere", workingDir))
                .isEqualTo(workingDir.resolve("elsewhere"));
    }

    @Test
    void nullLogDirOverrideFallsBackToWorkingDirLog(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("log"));
        RecordingLauncher launcher = new RecordingLauncher();

        launcher.launch("127.0.0.1", 0, null, new StubDefaults(null), workingDir, null);

        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("log"));
    }
}
