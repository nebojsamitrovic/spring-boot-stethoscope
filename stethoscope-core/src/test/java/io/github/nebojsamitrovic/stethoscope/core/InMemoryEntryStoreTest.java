package io.github.nebojsamitrovic.stethoscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryEntryStoreTest {

    @Test
    void evictsOldestWhenFull() {
        InMemoryEntryStore store = new InMemoryEntryStore(3);
        for (long id = 1; id <= 5; id++) {
            store.store(entry(id, EntryType.QUERY, "b", Map.of()));
        }

        List<Entry> all = store.list(new EntryQuery(null, null, null, 10));
        assertEquals(List.of(5L, 4L, 3L), all.stream().map(Entry::id).toList());
        assertFalse(store.find(1).isPresent());
        assertEquals(3, store.count(EntryType.QUERY));
    }

    @Test
    void filtersByTypeTagAndSearch() {
        InMemoryEntryStore store = new InMemoryEntryStore(10);
        store.store(new Entry(1, "a", EntryType.REQUEST, Instant.now(),
                Map.of(Entry.Content.METHOD, "GET", Entry.Content.URI, "/orders"), Set.of()));
        store.store(new Entry(2, "a", EntryType.QUERY, Instant.now(),
                Map.of(Entry.Content.SQL, "select * from orders"), Set.of(Entry.Tags.SLOW)));
        store.store(new Entry(3, "b", EntryType.QUERY, Instant.now(),
                Map.of(Entry.Content.SQL, "select * from users"), Set.of()));

        assertEquals(List.of(3L, 2L), ids(store.list(EntryQuery.of(EntryType.QUERY))));
        assertEquals(List.of(2L), ids(store.list(new EntryQuery(null, Entry.Tags.SLOW, null, 0))));
        assertEquals(List.of(2L, 1L), ids(store.list(new EntryQuery(null, null, "ORDERS", 0))));
        assertEquals(List.of(1L, 2L), ids(store.batch("a")));
    }

    @Test
    void clearRemovesEverything() {
        InMemoryEntryStore store = new InMemoryEntryStore(10);
        store.store(entry(1, EntryType.EXCEPTION, null, Map.of()));
        store.clear();
        assertTrue(store.list(new EntryQuery(null, null, null, 0)).isEmpty());
        assertEquals(0, store.count(EntryType.EXCEPTION));
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryEntryStore(0));
    }

    private static Entry entry(long id, EntryType type, String batch, Map<String, Object> content) {
        return new Entry(id, batch, type, Instant.now(), content, Set.of());
    }

    private static List<Long> ids(List<Entry> entries) {
        return entries.stream().map(Entry::id).toList();
    }
}
