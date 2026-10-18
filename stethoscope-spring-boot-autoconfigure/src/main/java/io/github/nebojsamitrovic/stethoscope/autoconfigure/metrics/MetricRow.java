package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

/**
 * Everything one key of one metric did during one time bucket.
 *
 * @param bucketSeconds bucket size, {@link #MINUTE} or {@link #HOUR}
 * @param bucketStart   start of the bucket, epoch seconds
 * @param metric        what is counted
 * @param key           what it is counted for, e.g. a route or a statement
 * @param hits          number of occurrences
 * @param total         sum of their values (milliseconds for durations), for averages
 * @param peak          largest single value
 * @param lastSeen      last occurrence, epoch seconds
 * @param sample        a detail of the last occurrence, e.g. an exception message; may be {@code null}
 */
public record MetricRow(
        int bucketSeconds,
        long bucketStart,
        Metric metric,
        String key,
        long hits,
        long total,
        long peak,
        long lastSeen,
        String sample) {

    public static final int MINUTE = 60;
    public static final int HOUR = 3600;

    /** The average value, or 0 without hits. */
    public long average() {
        return hits == 0 ? 0 : total / hits;
    }
}
