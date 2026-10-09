package io.github.ecommercebench.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 当前 run 专用的有序内存实现。
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, Memo> memos = new LinkedHashMap<>();

    @Override
    public void add(String title, String content) {
        if (memos.containsKey(title)) {
            throw new IllegalArgumentException("备忘录已存在: " + title);
        }
        memos.put(title, new Memo(title, content));
    }

    @Override
    public Optional<Memo> get(String title) {
        return Optional.ofNullable(memos.get(title));
    }

    @Override
    public void update(String title, String content) {
        if (!memos.containsKey(title)) {
            throw new IllegalArgumentException("备忘录不存在: " + title);
        }
        memos.put(title, new Memo(title, content));
    }

    @Override
    public boolean delete(String title) {
        return memos.remove(title) != null;
    }

    @Override
    public List<Memo> list(int limit) {
        int effectiveLimit = Math.max(0, Math.min(limit, 20));
        return List.copyOf(
                new ArrayList<>(memos.values()).subList(0, Math.min(effectiveLimit, memos.size())));
    }
}
