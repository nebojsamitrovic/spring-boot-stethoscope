package io.github.nebojsamitrovic.stethoscope.autoconfigure.cache;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/** Delegating {@link CacheManager} that hands out {@link RecordingCache recording} caches. */
public class RecordingCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final Supplier<Recorder> recorder;
    private final Map<String, RecordingCache> caches = new ConcurrentHashMap<>();

    public RecordingCacheManager(CacheManager delegate, Supplier<Recorder> recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    /** The wrapped cache manager, for code that needs the concrete type. */
    public CacheManager getDelegate() {
        return delegate;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        if (cache == null) {
            return null;
        }
        // the delegate may replace a cache (e.g. after a refresh), so re-wrap when it changes
        return caches.compute(name, (key, existing) ->
                existing != null && existing.delegate() == cache ? existing : new RecordingCache(cache, recorder));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
