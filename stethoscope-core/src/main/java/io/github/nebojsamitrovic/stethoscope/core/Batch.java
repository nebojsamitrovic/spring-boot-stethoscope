package io.github.nebojsamitrovic.stethoscope.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Groups everything recorded during one unit of work (usually an HTTP request) and keeps
 * running statistics needed for the request summary: query count, total query time and
 * repeated statements (N+1 detection).
 *
 * <p>A batch is confined to the thread that started it, so it is not synchronized.
 */
public final class Batch {

    private final String id;
    private final long startedAtNanos;
    private int queryCount;
    private long queryTimeNanos;
    private final Map<String, Integer> statementCounts = new HashMap<>();
    private final Set<Throwable> recordedExceptions = Collections.newSetFromMap(new IdentityHashMap<>());

    Batch() {
        this(UUID.randomUUID().toString(), System.nanoTime());
    }

    Batch(String id, long startedAtNanos) {
        this.id = id;
        this.startedAtNanos = startedAtNanos;
    }

    public String id() {
        return id;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    void addQuery(String normalizedSql, long durationNanos) {
        queryCount++;
        queryTimeNanos += Math.max(0, durationNanos);
        statementCounts.merge(normalizedSql, 1, Integer::sum);
    }

    /** Returns {@code true} the first time a given throwable instance is seen in this batch. */
    boolean markExceptionRecorded(Throwable throwable) {
        return recordedExceptions.add(throwable);
    }

    public boolean hasExceptions() {
        return !recordedExceptions.isEmpty();
    }

    public int queryCount() {
        return queryCount;
    }

    public long queryTimeMillis() {
        return queryTimeNanos / 1_000_000;
    }

    /**
     * Statements executed at least {@code threshold} times, most repeated first.
     * A non-empty result usually means an N+1 problem.
     */
    public Map<String, Integer> repeatedStatements(int threshold) {
        Map<String, Integer> result = new LinkedHashMap<>();
        statementCounts.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }
}
