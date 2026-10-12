package io.github.ecommercebench.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** serve 的供应商名册路径解析测试：--catalog 覆盖优先，否则回退到资源根 data/suppliers.csv。 */
class ServeCommandCatalogTest {

    @Test
    void defaultsToDataDirUnderWorkingDir(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        Path catalog = ServeCommand.resolveCatalog(null, workingDir);

        assertThat(catalog).isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }

    @Test
    void explicitOverrideWins(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));
        Path custom = workingDir.resolve("elsewhere/roster.csv");

        assertThat(ServeCommand.resolveCatalog(custom.toString(), workingDir)).isEqualTo(custom);
    }

    @Test
    void blankOverrideIsIgnored(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        assertThat(ServeCommand.resolveCatalog("   ", workingDir))
                .isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }
}
