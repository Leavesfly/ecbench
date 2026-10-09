package io.github.ecommercebench.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 在上下文裁剪后仍可读取的 run-local 记忆接口。
 */
public interface MemoryStore {
    void add(String title, String content);

    Optional<Memo> get(String title);

    void update(String title, String content);

    boolean delete(String title);

    List<Memo> list(int limit);
}
