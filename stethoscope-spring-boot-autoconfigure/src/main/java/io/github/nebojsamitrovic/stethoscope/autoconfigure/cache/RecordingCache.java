package io.github.nebojsamitrovic.stethoscope.autoconfigure.cache;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.Bodies;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.springframework.cache.Cache;

/** Delegating {@link Cache} that records hits, misses, puts and evictions as {@link EntryType#CACHE} entries. */
public class RecordingCache implements Cache {

    static final String HIT = "hit";
    static final String MISS = "miss";
    static final String PUT = "put";
    static final String EVICT = "evict";
    static final String CLEAR = "clear";

    private static final int MAX_TEXT = 1000;
    private static final Object NO_VALUE = new Object();

    private final Cache delegate;
    private final Supplier<Recorder> recorder;

    public RecordingCache(Cache delegate, Supplier<Recorder> recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    Cache delegate() {
        return delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper value = delegate.get(key);
        record(value != null ? HIT : MISS, key, value != null ? value.get() : NO_VALUE);
        return value;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        T value = delegate.get(key, type);
        record(value != null ? HIT : MISS, key, value != null ? value : NO_VALUE);
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        boolean[] loaded = {false};
        T value = delegate.get(key, () -> {
            loaded[0] = true;
            return valueLoader.call();
        });
        record(loaded[0] ? MISS : HIT, key, value);
        return value;
    }

    @Override
    public CompletableFuture<?> retrieve(Object key) {
        return delegate.retrieve(key);
    }

    @Override
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return delegate.retrieve(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value);
        record(PUT, key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper existing = delegate.putIfAbsent(key, value);
        if (existing == null) {
            record(PUT, key, value);
        }
        return existing;
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
        record(EVICT, key, NO_VALUE);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean evicted = delegate.evictIfPresent(key);
        if (evicted) {
            record(EVICT, key, NO_VALUE);
        }
        return evicted;
    }

    @Override
    public void clear() {
        delegate.clear();
        record(CLEAR, NO_VALUE, NO_VALUE);
    }

    @Override
    public boolean invalidate() {
        boolean invalidated = delegate.invalidate();
        record(CLEAR, NO_VALUE, NO_VALUE);
        return invalidated;
    }

    private void record(String operation, Object key, Object value) {
        try {
            Recorder target = recorder.get();
            if (target == null || !target.isRecording()) {
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.CACHE_NAME, delegate.getName());
            content.put(Entry.Content.OPERATION, operation);
            if (key != NO_VALUE) {
                content.put(Entry.Content.KEY, Bodies.abbreviate(key, MAX_TEXT));
            }
            if (value != NO_VALUE) {
                content.put(Entry.Content.VALUE, Bodies.abbreviate(value, MAX_TEXT));
            }
            target.record(EntryType.CACHE, content, Set.of(operation));
        } catch (RuntimeException ignored) {
            // never break caching because of the debugger
        }
    }
}
