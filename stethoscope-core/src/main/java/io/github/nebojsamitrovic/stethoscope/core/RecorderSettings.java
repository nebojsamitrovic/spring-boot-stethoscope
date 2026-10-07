package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Tuning knobs for {@link Recorder}.
 *
 * @param slowQueryMillis          queries at or above this duration get the {@code slow} tag
 * @param duplicateQueryThreshold  a statement repeated this many times in one batch flags the request as N+1
 * @param recordQueryParameters    store bound parameter values of queries
 * @param maxStackTraceFrames      frames kept per throwable in a cause chain
 */
public record RecorderSettings(
        long slowQueryMillis,
        int duplicateQueryThreshold,
        boolean recordQueryParameters,
        int maxStackTraceFrames) {

    public RecorderSettings {
        if (slowQueryMillis < 0) {
            throw new IllegalArgumentException("slowQueryMillis must be >= 0");
        }
        if (duplicateQueryThreshold < 2) {
            throw new IllegalArgumentException("duplicateQueryThreshold must be >= 2");
        }
        if (maxStackTraceFrames < 1) {
            throw new IllegalArgumentException("maxStackTraceFrames must be >= 1");
        }
    }

    public static RecorderSettings defaults() {
        return new RecorderSettings(100, 5, true, 40);
    }
}
