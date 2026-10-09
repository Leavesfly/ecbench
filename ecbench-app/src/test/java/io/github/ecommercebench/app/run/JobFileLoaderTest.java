package io.github.ecommercebench.app.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.llm.model.ChatRole;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * JobFileLoader 契约测试：逐行解析 JSONL、保留关键字段、跳过空行、缺失值回退默认、非法行报告行号。
 */
class JobFileLoaderTest {

    private static final Path JOBS =
            Path.of(System.getProperty("user.dir"), "src", "test", "resources", "jobs");

    private final JobFileLoader loader = new JobFileLoader(new ObjectMapper(), RunConfig.defaults());

    @Test
    void loadsSingleJobPreservingFields() {
        List<RunJob> jobs = loader.load(JOBS.resolve("single.jsonl"));

        assertThat(jobs).hasSize(1);
        RunJob job = jobs.get(0);
        assertThat(job.task()).isEqualTo("agent_multiturn/long_horizon/ecommerce_bench");
        assertThat(job.idx()).isZero();
        assertThat(job.dataSource()).isEqualTo("ecommerce_bench");
        assertThat(job.maxTurns()).isEqualTo(100);
        assertThat(job.maxDays()).isEqualTo(30);
        assertThat(job.maxToolResponseChars()).isEqualTo(4096);
        assertThat(job.initialMessages()).hasSize(2);
        assertThat(job.initialMessages().get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(job.initialMessages().get(1).content())
                .isEqualTo("You are running an e-commerce business.");
        assertThat(job.toolSchemas()).hasSize(1);
        assertThat(job.toolSchemas().get(0).name()).isEqualTo("check_balance");
    }

    @Test
    void loadsMultipleJobsSkippingBlankLinesAndApplyingDefaults() {
        List<RunJob> jobs = loader.load(JOBS.resolve("multiple.jsonl"));

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).idx()).isZero();
        assertThat(jobs.get(1).idx()).isEqualTo(1);
        assertThat(jobs.get(1).task()).isEqualTo("agent_multiturn/long_horizon/ecommerce_bench");
        assertThat(jobs.get(1).dataSource()).isEqualTo("ecommerce_bench");
        assertThat(jobs.get(1).maxTurns()).isEqualTo(RunConfig.defaults().maxTurns());
        assertThat(jobs.get(1).toolSchemas()).hasSize(1);
        assertThat(jobs.get(1).toolSchemas().get(0).name()).isEqualTo("wait_for_next_day");
    }

    @Test
    void reportsLineNumberOnInvalidJson() {
        assertThatThrownBy(() -> loader.load(JOBS.resolve("invalid.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }
}
