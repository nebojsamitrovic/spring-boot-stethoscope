package io.github.nebojsamitrovic.stethoscope.core;

import java.util.List;
import java.util.Optional;

/**
 * Keeps nothing. Used when individual entries are not wanted (typically in production), while
 * {@link EntryListener listeners} such as the overview metrics still see every entry as it is recorded.
 */
public class DiscardingEntryStore implements EntryStore {

    @Override
    public void store(Entry entry) {
    }

    @Override
    public Optional<Entry> find(long id) {
        return Optional.empty();
    }

    @Override
    public List<Entry> list(EntryQuery query) {
        return List.of();
    }

    @Override
    public List<Entry> batch(String batchId) {
        return List.of();
    }

    @Override
    public long count(EntryType type) {
        return 0;
    }

    @Override
    public void clear() {
    }
}
