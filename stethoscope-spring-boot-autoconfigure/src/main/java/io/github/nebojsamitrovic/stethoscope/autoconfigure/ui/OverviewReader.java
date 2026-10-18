package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.Metric;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricsStore;
import java.time.Clock;

/** Reads everything the overview shows for one period. */
class OverviewReader {

    static final int TOP = 10;

    private final MetricsStore store;
    private final StethoscopeProperties properties;
    private final Clock clock;

    OverviewReader(MetricsStore store, StethoscopeProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    Overview read(Period period) {
        long now = clock.instant().getEpochSecond();
        long from = period.from(now);
        int size = period.bucketSeconds();
        return new Overview(
                period, now, from,
                store.series(Metric.THROUGHPUT, size, from),
                store.series(Metric.CPU, size, from),
                store.series(Metric.HEAP, size, from),
                store.top(Metric.SLOW_REQUEST, size, from, MetricsStore.Order.HITS, TOP),
                store.top(Metric.SLOW_QUERY, size, from, MetricsStore.Order.HITS, TOP),
                store.top(Metric.EXCEPTION, size, from, MetricsStore.Order.HITS, TOP),
                store.top(Metric.JOB, size, from, MetricsStore.Order.PEAK, TOP),
                store.top(Metric.FAILED_JOB, size, from, MetricsStore.Order.HITS, TOP),
                store.top(Metric.SLOW_OUTGOING, size, from, MetricsStore.Order.HITS, TOP),
                properties.getRequests().getSlowThreshold().toMillis(),
                properties.getQueries().getSlowThreshold().toMillis(),
                properties.getHttpClient().getSlowThreshold().toMillis());
    }
}
