package io.github.ecommercebench.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunComponents;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.llm.model.LlmResponse;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.WebApplicationContext;

/**
 * 应用启动与组件工厂测试：无 Web 容器启动；每 run 独立引擎/内存/内核，共享只读 CatalogData。
 */
@SpringBootTest(
        classes = {EcommerceBenchApplication.class, EcommerceBenchApplicationTest.FakeLlmConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EcommerceBenchApplicationTest {

    // java-impl 隔离资源根（本模块 user.dir = java-impl/ecbench-app，上一级即 java-impl）。
    private static final Path JAVA_IMPL = Path.of(System.getProperty("user.dir"), "..").normalize();

    @Autowired
    private ApplicationContext context;
    @Autowired
    private RunComponentFactory factory;

    /**
     * 用离线假 LLM 覆盖默认 provider，使 create() 不需要真实凭据或网络。
     */
    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        LlmClientProvider fakeLlmClientProvider() {
            return config -> request -> new LlmResponse("", List.of(), null, List.of(), null);
        }
    }

    private static BenchmarkOptions options(Path logDir) {
        RunConfig runConfig =
                new RunConfig(
                        "gpt-5.6-sol",
                        16_384,
                        4_000,
                        3,
                        Money.of("100000"),
                        Money.of("50"),
                        128_000,
                        JAVA_IMPL.resolve("tokenizer"),
                        JAVA_IMPL.resolve("data"),
                        logDir,
                        null,
                        1,
                        42L);
        return new BenchmarkOptions(
                runConfig, ContextConfig.defaults(), JAVA_IMPL.resolve("models_config.json"), null);
    }

    @Test
    void startsNonWebApplicationContext() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBean(RunComponentFactory.class)).isSameAs(factory);
    }

    @Test
    void createsIsolatedRunLocalComponentsSharingCatalog(@TempDir Path tempDir) {
        BenchmarkOptions options = options(tempDir);

        try (RunComponents a = factory.create(0, options);
             RunComponents b = factory.create(1, options)) {
            assertThat(a.engine()).isNotSameAs(b.engine());
            assertThat(a.engine().catalog()).isSameAs(b.engine().catalog());
            assertThat(a.observer()).isNotSameAs(b.observer());
        }
    }
}
