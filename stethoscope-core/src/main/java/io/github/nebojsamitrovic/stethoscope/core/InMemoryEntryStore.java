package io.github.nebojsamitrovic.stethoscope.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, thread-safe ring buffer with one buffer per {@link EntryType}. When a type's buffer is
 * full, its oldest entry is evicted, so a chatty watcher (logs, cache) never pushes requests out.
 *
 * <p>Nothing survives a restart, which is exactly what you want from a dev tool.
 */
public class InMemoryEntryStore implements EntryStore {

    private final int capacity;
    private final Map<EntryType, Deque<Entry>> entries = new EnumMap<>(EntryType.class);
    private final Map<Long, Entry> byId = new HashMap<>();

    /** @param capacity maximum number of entries kept per type */
    public InMemoryEntryStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    /** Maximum number of entries kept per type. */
    public int capacity() {
        return capacity;
    }

    @Override
    public synchronized void store(Entry entry) {
        Deque<Entry> buffer = entries.computeIfAbsent(entry.type(), t -> new ArrayDeque<>(Math.min(capacity, 256)));
        while (buffer.size() >= capacity) {
            byId.remove(buffer.pollFirst().id());
        }
        buffer.addLast(entry);
        byId.put(entry.id(), entry);
    }

    @Override
    public synchronized Optional<Entry> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized List<Entry> list(EntryQuery query) {
        if (query.type() != null) {
            return newestMatching(entries.getOrDefault(query.type(), new ArrayDeque<>()), query);
        }
        List<Entry> result = new ArrayList<>();
        for (Deque<Entry> buffer : entries.values()) {
            result.addAll(newestMatching(buffer, query));
        }
        result.sort(Comparator.comparingLong(Entry::id).reversed());
        return Collections.unmodifiableList(result.subList(0, Math.min(query.limit(), result.size())));
    }

    private static List<Entry> newestMatching(Deque<Entry> buffer, EntryQuery query) {
        List<Entry> result = new ArrayList<>(Math.min(query.limit(), buffer.size()));
        Iterator<Entry> newestFirst = buffer.descendingIterator();
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
        for (Deque<Entry> buffer : entries.values()) {
            for (Entry entry : buffer) {
                if (batchId.equals(entry.batchId())) {
                    result.add(entry);
                }
            }
        }
        result.sort(Comparator.comparingLong(Entry::id));
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized long count(EntryType type) {
        Deque<Entry> buffer = entries.get(type);
        return buffer == null ? 0 : buffer.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        byId.clear();
    }
}
