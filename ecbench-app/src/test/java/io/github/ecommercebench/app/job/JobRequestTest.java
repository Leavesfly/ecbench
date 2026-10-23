package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.app.config.RunOptions;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** JobRequest 与 CliRunOptions 的契约一致性：访问器集合、默认值、路径类字段不可由 body 覆盖。 */
class JobRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 服务进程启动参数：4 个路径类字段的来源。 */
    private static final RunOptions SERVER =
            new JobRequest(
                    "unused", 1, 1, 1, 1.0, 1.0, 1, 1, null,
                    "/srv/tokenizer", "/srv/log", "/srv/job.jsonl", "/srv/data");

    private static CliRunOptions cli(String... args) {
        CliRunOptions options = new CliRunOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    private static ObjectNode body(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void recordComponentsMatchTheRunOptionsContractExactly() {
        Set<String> contract = accessorNames(RunOptions.class);
        Set<String> components =
                Arrays.stream(JobRequest.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toSet());

        assertThat(components).isEqualTo(contract);
        assertThat(contract).hasSize(13);
    }

    @Test
    void cliRunOptionsExposesEveryContractAccessor() {
        assertThat(accessorNames(CliRunOptions.class)).containsAll(accessorNames(RunOptions.class));
    }

    @Test
    void defaultsMatchCliRunOptionsValueForValue() throws Exception {
        CliRunOptions cli = cli("--model", "gpt");

        JobRequest web = JobRequest.parse(body("{\"model\":\"gpt\"}"), SERVER);

        assertThat(web.maxTokens()).isEqualTo(cli.maxTokens());
        assertThat(web.maxTurns()).isEqualTo(cli.maxTurns());
        assertThat(web.maxDays()).isEqualTo(cli.maxDays());
        assertThat(web.initialBalance()).isEqualTo(cli.initialBalance());
        assertThat(web.dailyFee()).isEqualTo(cli.dailyFee());
        assertThat(web.maxTokenCapacity()).isEqualTo(cli.maxTokenCapacity());
        assertThat(web.runs()).isEqualTo(cli.runs());
        // seed 缺省必须是 null：42 由 ConfigurationMerger.DEFAULT_SEED 唯一持有，两处各写会静默分叉。
        assertThat(web.seed()).isEqualTo(cli.seed()).isNull();
    }

    @Test
    void pathFieldsAlwaysComeFromServerNeverFromBody() throws Exception {
        ObjectNode node = body("{\"model\":\"gpt\"}");
        node.put("logDir", "/attacker/log");
        node.put("dataDir", "/attacker/data");
        node.put("tokenizerPath", "/attacker/tokenizer.json");
        node.put("jobFile", "/attacker/job.jsonl");

        JobRequest web = JobRequest.parse(node, SERVER);

        assertThat(web.logDir()).isEqualTo("/srv/log");
        assertThat(web.dataDir()).isEqualTo("/srv/data");
        assertThat(web.tokenizerPath()).isEqualTo("/srv/tokenizer");
        assertThat(web.jobFile()).isEqualTo("/srv/job.jsonl");
    }

    @Test
    void explicitNumbersOverrideDefaults() throws Exception {
        JobRequest web =
                JobRequest.parse(
                        body("{\"model\":\"gpt\",\"maxDays\":10,\"maxTurns\":50,\"runs\":3,\"seed\":7}"), SERVER);

        assertThat(web.maxDays()).isEqualTo(10);
        assertThat(web.maxTurns()).isEqualTo(50);
        assertThat(web.runs()).isEqualTo(3);
        assertThat(web.seed()).isEqualTo(7L);
    }

    @Test
    void modelIsTrimmed() throws Exception {
        assertThat(JobRequest.parse(body("{\"model\":\"  gpt  \"}"), SERVER).model()).isEqualTo("gpt");
    }

    @Test
    void rejectsNullNonObjectAndMissingModel() throws Exception {
        assertThatThrownBy(() -> JobRequest.parse(null, SERVER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobRequest.parse(MAPPER.readTree("[1,2]"), SERVER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobRequest.parse(body("{\"maxDays\":2}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"   \"}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsNonPositiveNumbers() throws Exception {
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"maxDays\":0}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDays");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"runs\":-1}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"dailyFee\":-1}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyFee");
    }

    @Test
    void rejectsFractionalAndTextualNumbers() throws Exception {
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"runs\":1.5}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"seed\":\"abc\"}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    @Test
    void explicitJsonNullFallsBackToDefaults() throws Exception {
        JobRequest web = JobRequest.parse(body("{\"model\":\"g\",\"maxDays\":null}"), SERVER);

        assertThat(web.maxDays()).isEqualTo(JobRequest.DEFAULT_MAX_DAYS);
    }

    @Test
    void threeArgParseUsesTheJobFileOverride() throws Exception {
        JobRequest request =
                JobRequest.parse(body("{\"model\":\"gpt\"}"), SERVER, "/tmp/uploaded-123.jsonl");

        assertThat(request.jobFile()).isEqualTo("/tmp/uploaded-123.jsonl");
        // 其余三个路径类字段仍取服务进程配置，不受 override 影响
        assertThat(request.logDir()).isEqualTo(SERVER.logDir());
        assertThat(request.dataDir()).isEqualTo(SERVER.dataDir());
        assertThat(request.tokenizerPath()).isEqualTo(SERVER.tokenizerPath());
    }

    /**
     * 两参重载必须完全委派给三参版并传 {@code server.jobFile()}。
     *
     * <p>{@code JobRequest} 是 record，{@code equals} 比全部 13 个字段，故这一条锁的是「委派不丢任何字段」；
     * 单独的 jobFile 取值已由 {@link #pathFieldsAlwaysComeFromServerNeverFromBody} 覆盖，不重复断言。
     */
    @Test
    void twoArgParseDelegatesCompletelyToTheThreeArgOverload() throws Exception {
        ObjectNode node = body("{\"model\":\"gpt\",\"runs\":2,\"seed\":7}");

        assertThat(JobRequest.parse(node, SERVER))
                .isEqualTo(JobRequest.parse(node, SERVER, SERVER.jobFile()));
    }

    private static Set<String> accessorNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 0)
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
