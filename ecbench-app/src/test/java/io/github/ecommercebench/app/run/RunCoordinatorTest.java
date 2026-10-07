package io.github.ecommercebench.app.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.TerminationReason;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** RunCoordinator 契约测试：全部 run 都执行、失败隔离、结果按 index 排序、线程池正常关闭、池大小受限。 */
class RunCoordinatorTest {

  private static BenchmarkOptions options(int runs) {
    RunConfig runConfig =
        new RunConfig(
            "m",
            16_384,
            4_000,
            3,
            Money.of("100000"),
            Money.of("50"),
            128_000,
            null,
            Path.of("data"),
            null,
            null,
            runs,
            42L);
    return new BenchmarkOptions(
        runConfig, ContextConfig.defaults(), Path.of("models_config.json"), null);
  }

  private static RunResult fakeResult(int day) {
    return new RunResult(
        TerminationReason.ENV_COMPLETED,
        "max_days",
        1,
        List.of(),
        day,
        "2026-01-02",
        100000.0,
        0,
        0);
  }

  @Test
  void runsAllIsolatingFailureAndSortingByIndex() {
    RunCoordinator coordinator = new RunCoordinator(null);
    Set<Integer> executed = ConcurrentHashMap.newKeySet();
    RunExecutor executor =
        (index, opts) -> {
          executed.add(index);
          if (index == 1) {
            throw new IllegalStateException("boom");
          }
          return new RunOutcome(index, fakeResult(index), null);
        };

    List<RunOutcome> outcomes = coordinator.run(options(3), executor);

    assertThat(executed).containsExactlyInAnyOrder(0, 1, 2);
    assertThat(outcomes).extracting(RunOutcome::index).containsExactly(0, 1, 2);
    assertThat(outcomes.get(1).failure()).isNotNull();
    assertThat(outcomes.get(1).result()).isNull();
    assertThat(outcomes.get(0).result()).isNotNull();
    assertThat(outcomes.get(2).result()).isNotNull();
  }

  @Test
  void shutsDownExecutorAfterRun() {
    ExecutorService[] holder = new ExecutorService[1];
    RunCoordinator coordinator =
        new RunCoordinator(
            null,
            size -> {
              holder[0] = Executors.newFixedThreadPool(size);
              return holder[0];
            });
    RunExecutor executor = (index, opts) -> new RunOutcome(index, fakeResult(index), null);

    coordinator.run(options(2), executor);

    assertThat(holder[0]).isNotNull();
    assertThat(holder[0].isShutdown()).isTrue();
    assertThat(holder[0].isTerminated()).isTrue();
  }

  @Test
  void poolSizeIsBoundedByRunsAndProcessors() {
    int processors = Runtime.getRuntime().availableProcessors();
    assertThat(RunCoordinator.poolSize(1)).isEqualTo(1);
    assertThat(RunCoordinator.poolSize(1000)).isEqualTo(Math.min(1000, processors));
    assertThat(RunCoordinator.poolSize(processors)).isLessThanOrEqualTo(processors);
  }
}
