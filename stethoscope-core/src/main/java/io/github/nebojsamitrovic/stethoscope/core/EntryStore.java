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

    /**
     * Jobs dispatched from a batch, e.g. {@code @Async} calls made while handling a request, oldest
     * first. The default scans every stored job; a store with an index on the parent batch should
     * override it.
     */
    default List<Entry> jobsDispatchedBy(String batchId) {
        if (batchId == null) {
            return List.of();
        }
        return list(new EntryQuery(EntryType.JOB, null, null, Integer.MAX_VALUE)).stream()
                .filter(job -> batchId.equals(job.getString(Entry.Content.PARENT_BATCH, null)))
                .sorted(java.util.Comparator.comparingLong(Entry::id))
                .toList();
    }

    /** The batch that dispatched {@code entry}'s batch, or an empty list when it was not dispatched. */
    default List<Entry> dispatchingBatch(Entry entry) {
        String parent = entry.getString(Entry.Content.PARENT_BATCH, null);
        return parent == null ? List.of() : batch(parent);
    }

    /** Number of entries currently stored, per type. */
    long count(EntryType type);

    void clear();
}
