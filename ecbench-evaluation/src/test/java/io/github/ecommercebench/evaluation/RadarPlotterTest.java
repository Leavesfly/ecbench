package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 雷达图绘制测试：headless 生成非空 PNG，NaN 归一化值回退 0，空画像报错。
 */
class RadarPlotterTest {

    private static List<Double> filled(double... values) {
        Double[] boxed = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return Arrays.asList(boxed);
    }

    private static NormalizedProfile twoModelProfile() {
        return new NormalizedProfile(
                List.of(
                        new ModelProfile(
                                "modelA",
                                filled(1, 1, 1, 1, 1, 1, 1),
                                filled(0.9, 0.8, 0.7, 0.6, 0.5, 0.4, Double.NaN)),
                        new ModelProfile(
                                "modelB", filled(1, 1, 1, 1, 1, 1, 1), filled(0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8))),
                filled(0.55, 0.55, 0.55, 0.55, 0.55, 0.55, 0.4));
    }

    @Test
    void writesNonEmptyPng(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("nested/radar.png");

        Path written =
                new RadarPlotter()
                        .plot(twoModelProfile(), new RadarPlotter.RadarOptions(output, "profile"));

        assertThat(written).exists();
        assertThat(written.toString()).endsWith(".png");
        assertThat(Files.size(written)).isGreaterThan(0);
    }

    @Test
    void rejectsEmptyProfile(@TempDir Path tempDir) {
        NormalizedProfile empty = new NormalizedProfile(List.of(), filled(0, 0, 0, 0, 0, 0, 0));
        assertThatThrownBy(
                () ->
                        new RadarPlotter()
                                .plot(empty, new RadarPlotter.RadarOptions(tempDir.resolve("r.png"), "t")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
