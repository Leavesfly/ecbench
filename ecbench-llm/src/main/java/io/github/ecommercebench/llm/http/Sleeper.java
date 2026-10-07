package io.github.ecommercebench.llm.http;

import java.time.Duration;

/** 可替换的等待器，使重试策略无需在测试中真实休眠。 */
@FunctionalInterface
public interface Sleeper {
  void sleep(Duration duration) throws InterruptedException;
}
