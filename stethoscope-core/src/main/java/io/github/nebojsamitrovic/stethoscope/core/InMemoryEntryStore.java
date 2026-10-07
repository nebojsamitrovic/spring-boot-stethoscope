package io.github.nebojsamitrovic.stethoscope.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, thread-safe ring buffer. When full, the oldest entry is evicted.
 *
 * <p>Nothing survives a restart, which is exactly what you want from a dev tool.
 */
public class InMemoryEntryStore implements EntryStore {

    private final int capacity;
    private final Deque<Entry> entries;
    private final Map<Long, Entry> byId = new HashMap<>();
    private final Map<EntryType, Long> counts = new EnumMap<>(EntryType.class);

    public InMemoryEntryStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(capacity, 4096));
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public synchronized void store(Entry entry) {
        while (entries.size() >= capacity) {
            Entry evicted = entries.pollFirst();
            byId.remove(evicted.id());
            counts.merge(evicted.type(), -1L, Long::sum);
        }
        entries.addLast(entry);
        byId.put(entry.id(), entry);
        counts.merge(entry.type(), 1L, Long::sum);
    }

    @Override
    public synchronized Optional<Entry> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized List<Entry> list(EntryQuery query) {
        List<Entry> result = new ArrayList<>(Math.min(query.limit(), entries.size()));
        Iterator<Entry> newestFirst = entries.descendingIterator();
        while (newestFirst.hasNext() && result.size() < query.limit()) {
            Entry entry = newestFirst.next();
            if (query.matches(entry)) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized List<Entry> batch(String batchId) {
        if (batchId == null) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (batchId.equals(entry.batchId())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized long count(EntryType type) {
        return counts.getOrDefault(type, 0L);
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        byId.clear();
        counts.clear();
    }
}
