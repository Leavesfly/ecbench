package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** .env 解析契约：只取 KEY=VALUE，容忍缺文件/空文件/畸形行，且永不抛异常。 */
class SecretsFileLoaderTest {

    private final SecretsFileLoader loader = new SecretsFileLoader();

    @Test
    void nullPathYieldsEmptyMap() {
        assertThat(loader.read(null)).isEmpty();
    }

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertThat(loader.read(dir.resolve("absent.env"))).isEmpty();
    }

    @Test
    void parsesPairsAndIgnoresCommentsAndBlanks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "# 注释行\n\nOPENAI_API_KEY=sk-abc\n  \nDASHSCOPE_API_KEY=sk-def\n");

        Map<String, String> parsed = loader.read(file);

        assertThat(parsed).containsOnlyKeys("OPENAI_API_KEY", "DASHSCOPE_API_KEY");
        assertThat(parsed.get("OPENAI_API_KEY")).isEqualTo("sk-abc");
        assertThat(parsed.get("DASHSCOPE_API_KEY")).isEqualTo("sk-def");
    }

    @Test
    void stripsMatchingSurroundingQuotes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "A=\"quoted\"\nB='single'\nC=\"mismatched'\n");

        Map<String, String> parsed = loader.read(file);

        assertThat(parsed.get("A")).isEqualTo("quoted");
        assertThat(parsed.get("B")).isEqualTo("single");
        assertThat(parsed.get("C")).isEqualTo("\"mismatched'");
    }

    @Test
    void skipsMalformedLinesWithoutThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "no-equals-sign\n=empty-key\nGOOD=value\n");

        assertThat(loader.read(file)).containsExactly(Map.entry("GOOD", "value"));
    }

    @Test
    void emptyValueIsKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "BLANK=\n");

        assertThat(loader.read(file)).containsEntry("BLANK", "");
    }

    @Test
    void inlineCommentIsKeptAsPartOfTheValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "KEY=value # not-a-comment\n");

        assertThat(loader.read(file)).containsEntry("KEY", "value # not-a-comment");
    }

    @Test
    void directoryPathYieldsEmptyMap(@TempDir Path dir) throws Exception {
        Path notAFile = dir.resolve("a-directory");
        Files.createDirectories(notAFile);

        assertThat(loader.read(notAFile)).isEmpty();
    }

    @Test
    void valueContainingEqualsSignIsSplitOnFirstOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "TOKEN=abc=def==\n");

        assertThat(loader.read(file)).containsEntry("TOKEN", "abc=def==");
    }
}
