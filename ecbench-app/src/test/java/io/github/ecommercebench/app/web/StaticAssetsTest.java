package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验 dashboard 静态资源随 jar 打包在 classpath 的 {@code web/} 下，且入口页正确引用脚本与样式。
 */
class StaticAssetsTest {

    private String read(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 资源缺失: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundlesAllDashboardAssets() throws IOException {
        for (String resource : List.of("web/index.html", "web/app.css", "web/app.js")) {
            assertThat(read(resource)).isNotBlank();
        }
    }

    @Test
    void indexReferencesLocalAssetsAndChartLibrary() throws IOException {
        String html = read("web/index.html");

        assertThat(html).contains("app.css").contains("app.js");
        assertThat(html).contains("chart");
        assertThat(html).contains("id=\"radar\"").contains("id=\"balance-chart\"");
    }

    @Test
    void appJsCallsEveryApiEndpoint() throws IOException {
        String js = read("web/app.js");

        assertThat(js).contains("/api/sessions");
        assertThat(js).contains("/api/compare");
        assertThat(js).contains("/analysis");
        assertThat(js).contains("/balance");
        assertThat(js).contains("/chatbox");
    }
}
