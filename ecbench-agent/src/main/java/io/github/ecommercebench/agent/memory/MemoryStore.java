package io.github.ecommercebench.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 在上下文裁剪后仍可读取的 run-local 记忆接口。
 */
public interface MemoryStore {
    /** 新增备忘录；标题已存在时抛出 {@link IllegalArgumentException}。 */
    void add(String title, String content);

    /** 按标题取回备忘录，不存在时返回空。 */
    Optional<Memo> get(String title);

    /** 覆盖更新指定标题的内容；不存在时抛出 {@link IllegalArgumentException}。 */
    void update(String title, String content);

    /** 删除备忘录，返回是否实际删除。 */
    boolean delete(String title);

    /** 按插入顺序返回至多 limit（上限 20）条备忘录。 */
    List<Memo> list(int limit);
}
