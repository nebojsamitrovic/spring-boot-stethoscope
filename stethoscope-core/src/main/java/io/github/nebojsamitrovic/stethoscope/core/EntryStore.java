package io.github.nebojsamitrovic.stethoscope.core;

import java.util.List;
import java.util.Optional;

/**
 * Where entries live. The default is {@link InMemoryEntryStore}; provide your own bean to persist
 * entries elsewhere (JDBC, Redis...).
 *
 * <p>Implementations must be thread-safe: entries are written from request threads while the UI reads.
 */
public interface EntryStore {

    /** Stores an entry. May evict older entries to stay within capacity. */
    void store(Entry entry);

    Optional<Entry> find(long id);

    /** Entries matching the query, newest first. */
    List<Entry> list(EntryQuery query);

    /** All entries of one batch (e.g. one HTTP request), oldest first. */
    List<Entry> batch(String batchId);

    /** Number of entries currently stored, per type. */
    long count(EntryType type);

    void clear();
}
