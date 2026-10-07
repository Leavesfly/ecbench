package io.github.ecommercebench.app.run;

import io.github.ecommercebench.app.config.BenchmarkOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 并行 run 编排器，端口 Python {@code run.py} 的 {@code ThreadPoolExecutor} 分支。
 *
 * <p>为每个 run index 提交一个任务到固定线程池（大小 {@code min(runs, availableProcessors)}），每个任务捕获自身异常并返回 {@link
 * RunOutcome}，因此一个 run 失败不会取消其他 run。全部完成后按 index 排序返回；线程池在 finally 中 shutdown 并等待终止。 本类不调用 {@code
 * System.exit}，退出码由最外层 {@code main} 归并。
 */
public final class RunCoordinator {

  private static final long AWAIT_MINUTES = 5;

  private final RunComponentFactory factory;
  private final IntFunction<ExecutorService> poolFactory;

  public RunCoordinator(RunComponentFactory factory) {
    this(factory, Executors::newFixedThreadPool);
  }

  RunCoordinator(RunComponentFactory factory, IntFunction<ExecutorService> poolFactory) {
    this.factory = factory;
    this.poolFactory = poolFactory;
  }

  public List<RunOutcome> run(BenchmarkOptions options) {
    return run(options, this::executeOne);
  }

  List<RunOutcome> run(BenchmarkOptions options, RunExecutor executor) {
    int runs = options.runConfig().runs();
    ExecutorService pool = poolFactory.apply(poolSize(runs));
    try {
      List<Callable<RunOutcome>> tasks = new ArrayList<>();
      for (int i = 0; i < runs; i++) {
        final int index = i;
        tasks.add(
            () -> {
              try {
                return executor.execute(index, options);
              } catch (Throwable failure) {
                return new RunOutcome(index, null, failure);
              }
            });
      }
      List<RunOutcome> outcomes = new ArrayList<>();
      for (Future<RunOutcome> future : pool.invokeAll(tasks)) {
        outcomes.add(future.get());
      }
      outcomes.sort(Comparator.comparingInt(RunOutcome::index));
      return outcomes;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("并行 run 编排被中断", exception);
    } catch (ExecutionException exception) {
      throw new IllegalStateException("并行 run 编排失败", exception);
    } finally {
      pool.shutdown();
      awaitTermination(pool);
    }
  }

  private RunOutcome executeOne(int index, BenchmarkOptions options) {
    try (RunComponents components = factory.create(index, options)) {
      return new RunOutcome(index, components.agent().run(components.job()), null);
    }
  }

  static int poolSize(int runs) {
    return Math.max(1, Math.min(runs, Runtime.getRuntime().availableProcessors()));
  }

  private static void awaitTermination(ExecutorService pool) {
    try {
      if (!pool.awaitTermination(AWAIT_MINUTES, TimeUnit.MINUTES)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException exception) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
