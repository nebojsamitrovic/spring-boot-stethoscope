package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import java.util.List;

/**
 * Where metrics rows outlive the process. The default is {@link JdbcMetricsStore} on the application's
 * own database; declare a bean of your own to keep them elsewhere.
 */
public interface MetricsStore {

    /** Order of {@link #top}. */
    enum Order {
        /** Most occurrences first. */
        HITS,
        /** Largest single value first. */
        PEAK
    }

    /** Called once before the first write, e.g. to create the table. */
    void initialize();

    /** Adds rows to what is stored: counts add up, peaks and last-seen keep the larger value. */
    void write(List<MetricRow> rows);

    /**
     * One row per key of {@code metric}, summed over the buckets of size {@code bucketSeconds}
     * starting at or after {@code from}. {@link MetricRow#bucketStart()} is {@code from}; the sample
     * is the latest one.
     */
    List<MetricRow> top(Metric metric, int bucketSeconds, long from, Order order, int limit);

    /** Every stored row of {@code metric} in buckets of size {@code bucketSeconds} starting at or after {@code from}. */
    List<MetricRow> series(Metric metric, int bucketSeconds, long from);

    /** Deletes buckets of size {@code bucketSeconds} that start before {@code before}; returns how many rows went. */
    int purge(int bucketSeconds, long before);
}
