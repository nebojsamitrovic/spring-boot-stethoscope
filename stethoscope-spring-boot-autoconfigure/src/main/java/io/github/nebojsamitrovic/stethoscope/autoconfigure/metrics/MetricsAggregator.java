package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryListener;
import io.github.nebojsamitrovic.stethoscope.core.SqlNormalizer;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Folds entries into per-minute and per-hour counters held in memory until
 * {@link MetricsScheduler} writes them out. Recording a request costs a couple of map updates, never
 * a database round trip.
 *
 * <p>Memory is bounded by the number of distinct keys, not by traffic: a million requests to the
 * same route are one counter. Past {@code maxPendingSlots} new keys are dropped (and counted) until
 * the next write empties the map.
 */
public class MetricsAggregator implements EntryListener {

    static final int MAX_KEY_LENGTH = 1000;
    static final int MAX_SAMPLE_LENGTH = 1000;
    static final String NO_ROUTE = "(no route)";

    private static final int[] BUCKETS = {MetricRow.MINUTE, MetricRow.HOUR};

    private final int maxPendingSlots;
    private final ConcurrentHashMap<Slot, Accumulator> pending = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    public MetricsAggregator(int maxPendingSlots) {
        if (maxPendingSlots < 1) {
            throw new IllegalArgumentException("maxPendingSlots must be >= 1, was " + maxPendingSlots);
        }
        this.maxPendingSlots = maxPendingSlots;
    }

    @Override
    public void onEntry(Entry entry) {
        Instant at = entry.createdAt();
        long durationMs = entry.getLong(Entry.Content.DURATION_MS, 0);
        switch (entry.type()) {
            case REQUEST -> {
                int status = (int) entry.getLong(Entry.Content.STATUS, 0);
                add(Metric.THROUGHPUT, (status / 100) + "xx", durationMs, at, null);
                if (entry.hasTag(Entry.Tags.SLOW)) {
                    add(Metric.SLOW_REQUEST,
                            entry.getString(Entry.Content.METHOD, "") + " " + entry.getString(Entry.Content.ROUTE, NO_ROUTE),
                            durationMs, at, entry.getString(Entry.Content.URI, null));
                }
            }
            case QUERY -> {
                if (entry.hasTag(Entry.Tags.SLOW)) {
                    add(Metric.SLOW_QUERY, SqlNormalizer.normalize(entry.getString(Entry.Content.SQL, "")),
                            durationMs, at, null);
                }
            }
            case EXCEPTION -> {
                String location = entry.getString(Entry.Content.LOCATION, null);
                add(Metric.EXCEPTION,
                        entry.getString(Entry.Content.EXCEPTION_CLASS, "?") + (location == null ? "" : " @ " + location),
                        0, at, entry.getString(Entry.Content.MESSAGE, null));
            }
            case JOB, SCHEDULED -> {
                String task = entry.getString(Entry.Content.TASK, "?");
                add(Metric.JOB, task, durationMs, at, null);
                if (Boolean.FALSE.equals(entry.get(Entry.Content.SUCCESS))) {
                    add(Metric.FAILED_JOB, task, durationMs, at, entry.getString(Entry.Content.ERROR, null));
                }
            }
            case HTTP_CLIENT -> {
                if (entry.hasTag(Entry.Tags.SLOW)) {
                    String url = entry.getString(Entry.Content.URL, "");
                    add(Metric.SLOW_OUTGOING, entry.getString(Entry.Content.METHOD, "") + " " + origin(url),
                            durationMs, at, withoutQuery(url));
                }
            }
            default -> {
            }
        }
    }

    /** Counts one occurrence of {@code key} with {@code value} at {@code at}, in every bucket size. */
    public void add(Metric metric, String key, long value, Instant at, String sample) {
        String boundedKey = truncate(key, MAX_KEY_LENGTH);
        String boundedSample = truncate(sample, MAX_SAMPLE_LENGTH);
        long epoch = at.getEpochSecond();
        for (int size : BUCKETS) {
            Slot slot = new Slot(size, epoch - Math.floorMod(epoch, size), metric, boundedKey);
            pending.compute(slot, (s, acc) -> {
                Accumulator target = acc != null ? acc : admit();
                if (target != null) {
                    target.add(1, value, value, epoch, boundedSample);
                }
                return target;
            });
        }
    }

    /** Takes everything collected so far; what is recorded meanwhile goes into the next drain. */
    public List<MetricRow> drain() {
        List<MetricRow> rows = new ArrayList<>(pending.size());
        for (Slot slot : pending.keySet()) {
            Accumulator acc = pending.remove(slot);
            if (acc != null) {
                rows.add(acc.toRow(slot));
            }
        }
        return rows;
    }

    /** Puts rows back after a failed write, merged with whatever arrived since. */
    public void restore(List<MetricRow> rows) {
        for (MetricRow row : rows) {
            Slot slot = new Slot(row.bucketSeconds(), row.bucketStart(), row.metric(), row.key());
            pending.compute(slot, (s, acc) -> {
                Accumulator target = acc != null ? acc : admit();
                if (target != null) {
                    target.add(row.hits(), row.total(), row.peak(), row.lastSeen(), row.sample());
                }
                return target;
            });
        }
    }

    /** Keys waiting to be written. */
    public int pendingSlots() {
        return pending.size();
    }

    /** Occurrences lost because too many keys were waiting. */
    public long dropped() {
        return dropped.get();
    }

    private Accumulator admit() {
        if (pending.size() >= maxPendingSlots) {
            dropped.incrementAndGet();
            return null;
        }
        return new Accumulator();
    }

    /** {@code https://api.example.com:8443/v1/x?k=1} → {@code https://api.example.com:8443}. */
    static String origin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) {
                return withoutQuery(url);
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException ex) {
            return withoutQuery(url);
        }
    }

    /** Query strings of outgoing calls often carry API keys. */
    static String withoutQuery(String url) {
        int cut = url.indexOf('?');
        return cut < 0 ? url : url.substring(0, cut);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record Slot(int bucketSeconds, long bucketStart, Metric metric, String key) {
    }

    /** Mutated only inside {@code ConcurrentHashMap.compute}, which serializes access per slot. */
    private static final class Accumulator {

        private long hits;
        private long total;
        private long peak = Long.MIN_VALUE;
        private long lastSeen = Long.MIN_VALUE;
        private String sample;

        void add(long hits, long total, long peak, long seen, String sample) {
            this.hits += hits;
            this.total += total;
            this.peak = Math.max(this.peak, peak);
            if (seen >= lastSeen) {
                lastSeen = seen;
                if (sample != null) {
                    this.sample = sample;
                }
            }
        }

        MetricRow toRow(Slot slot) {
            return new MetricRow(slot.bucketSeconds(), slot.bucketStart(), slot.metric(), slot.key(),
                    hits, total, peak, lastSeen, sample);
        }
    }
}
