package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

/**
 * What a metrics row counts. Keys are chosen so their number stays small however much traffic there
 * is: a route pattern rather than a URI, a normalized statement rather than one with its values.
 */
public enum Metric {

    /** Every request; key is the status class ({@code 2xx}, {@code 5xx}...), value its duration. */
    THROUGHPUT("throughput"),
    /** Requests tagged slow; key is method and route pattern, sample the last URI. */
    SLOW_REQUEST("slow_request"),
    /** Queries tagged slow; key is the normalized statement. */
    SLOW_QUERY("slow_query"),
    /** Key is exception class and application location, sample the last message. */
    EXCEPTION("exception"),
    /** Every run of an {@code @Async} job or {@code @Scheduled} task; key is the task. */
    JOB("job"),
    /** Failed runs; key is the task, sample the last error. */
    FAILED_JOB("failed_job"),
    /** Outgoing HTTP calls tagged slow; key is method and origin, sample the last URL without query. */
    SLOW_OUTGOING("slow_outgoing"),
    /** Process CPU in tenths of a percent, one value per minute; key is the instance. */
    CPU("cpu"),
    /** Heap in use in MB, one value per minute; key is the instance, sample the maximum heap. */
    HEAP("heap");

    private final String id;

    Metric(String id) {
        this.id = id;
    }

    /** Value stored in the {@code metric} column. */
    public String id() {
        return id;
    }
}
