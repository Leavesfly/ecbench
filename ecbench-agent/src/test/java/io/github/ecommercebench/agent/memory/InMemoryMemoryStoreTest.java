package io.github.ecommercebench.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryMemoryStoreTest {

    @Test
    void supportsAddGetUpdateDeleteAndOrderedList() {
        MemoryStore store = new InMemoryMemoryStore();
        store.add("supplier", "good price");
        store.add("inventory", "buy less");

        assertThat(store.get("supplier")).get().extracting(Memo::content).isEqualTo("good price");
        store.update("supplier", "best price");
        assertThat(store.get("supplier")).get().extracting(Memo::content).isEqualTo("best price");
        assertThat(store.list(20)).extracting(Memo::title).containsExactly("supplier", "inventory");
        assertThat(store.delete("supplier")).isTrue();
        assertThat(store.get("supplier")).isEmpty();
    }

    @Test
    void listHonorsLimit() {
        MemoryStore store = new InMemoryMemoryStore();
        for (int index = 0; index < 25; index++) {
            store.add("m" + index, "v" + index);
        }

        assertThat(store.list(20)).hasSize(20);
    }
}
