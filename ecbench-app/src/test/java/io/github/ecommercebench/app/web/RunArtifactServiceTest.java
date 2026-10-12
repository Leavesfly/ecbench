package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArtifactServiceTest {

    private final RunArtifactService service = new RunArtifactService(new ObjectMapper());

    @Test
    void readsAnalysisAsPassthrough(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(
                session.resolve("metrics/run_0_analysis.json"),
                "{\"reward\":{\"termination_reason\":\"env_terminated\"}}");

        JsonNode node = service.analysis(session, 0);

        assertThat(node.path("reward").path("termination_reason").asText())
                .isEqualTo("env_terminated");
    }

    @Test
    void readsBalanceSeries(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("balance"));
        Files.writeString(
                session.resolve("balance/run_0_daily_balance.csv"),
                "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged\n"
                        + "2026-01-01,100000.00,0.00,100000.00,0,0,0.00\n"
                        + "2026-01-02,95386.90,0.00,95386.90,3,0,0.00\n");

        JsonNode node = service.balance(session, 0);

        assertThat(node.path("series")).hasSize(2);
        assertThat(node.path("series").get(1).path("total_balance").asDouble()).isEqualTo(95386.90);
        assertThat(node.path("series").get(1).path("open_stores").asInt()).isEqualTo(3);
    }

    @Test
    void groupsChatboxConversationsBySupplier(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("trajectories"));
        String call =
                "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"tc1\",\"function\":"
                        + "{\"name\":\"chatbox\",\"arguments\":\"{\\\"uid\\\":\\\"oak@a.com\\\"}\"}}]}";
        String reply = "{\"role\":\"tool\",\"tool_call_id\":\"tc1\",\"content\":\"{\\\"message\\\":\\\"message_sent\\\"}\"}";
        Files.writeString(
                session.resolve("trajectories/run_0_messages.jsonl"), call + "\n" + reply + "\n");

        JsonNode node = service.chatbox(session, 0);

        assertThat(node.path("suppliers")).hasSize(1);
        assertThat(node.path("suppliers").get(0).path("supplier").asText()).isEqualTo("oak@a.com");
        assertThat(node.path("suppliers").get(0).path("messages")).hasSize(2);
    }

    @Test
    void missingArtifactThrowsNotFound(@TempDir Path session) {
        assertThatThrownBy(() -> service.analysis(session, 0))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void rejectsNegativeRunIndexFromCaller(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(session.resolve("metrics/run_0_analysis.json"), "{}");
        assertThatThrownBy(() -> service.analysis(session, 7)).isInstanceOf(ArtifactNotFoundException.class);
    }
}
