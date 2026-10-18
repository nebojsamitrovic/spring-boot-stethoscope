package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricRow;
import java.util.List;

/** Everything the overview shows, read from the store in one go. Times are epoch seconds. */
record Overview(
        Period period,
        long now,
        long from,
        List<MetricRow> throughput,
        List<MetricRow> cpu,
        List<MetricRow> heap,
        List<MetricRow> slowRequests,
        List<MetricRow> slowQueries,
        List<MetricRow> exceptions,
        List<MetricRow> jobs,
        List<MetricRow> failedJobs,
        List<MetricRow> slowOutgoing,
        long slowRequestMillis,
        long slowQueryMillis,
        long slowOutgoingMillis) {
}
