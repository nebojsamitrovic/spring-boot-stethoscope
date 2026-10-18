package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricsAggregatorTest {

    private static final Instant AT = Instant.parse("2026-10-18T14:05:42Z");

    private final MetricsAggregator aggregator = new MetricsAggregator(100);

    @Test
    void requestsCountByStatusClassAndSlowOnesByRoute() {
        aggregator.onEntry(request(200, 40, "/orders/{id}", "/orders/1", Set.of()));
        aggregator.onEntry(request(204, 60, "/orders/{id}", "/orders/2", Set.of()));
        aggregator.onEntry(request(500, 1500, "/orders/{id}", "/orders/3", Set.of(Entry.Tags.SLOW)));

        List<MetricRow> minute = minuteRows();
        MetricRow ok = row(minute, Metric.THROUGHPUT, "2xx");
        assertThat(ok.hits()).isEqualTo(2);
        assertThat(ok.total()).isEqualTo(100);
        assertThat(ok.peak()).isEqualTo(60);
        assertThat(ok.bucketStart()).isEqualTo(Instant.parse("2026-10-18T14:05:00Z").getEpochSecond());

        MetricRow slow = row(minute, Metric.SLOW_REQUEST, "GET /orders/{id}");
        assertThat(slow.hits()).isEqualTo(1);
        assertThat(slow.sample()).isEqualTo("/orders/3");
        assertThat(row(minute, Metric.THROUGHPUT, "5xx").peak()).isEqualTo(1500);
    }

    @Test
    void everyOccurrenceLandsInAMinuteAndAnHourBucket() {
        aggregator.onEntry(request(200, 10, "/a", "/a", Set.of()));

        List<MetricRow> rows = aggregator.drain();
        assertThat(rows).extracting(MetricRow::bucketSeconds).containsExactlyInAnyOrder(MetricRow.MINUTE, MetricRow.HOUR);
        MetricRow hour = rows.stream().filter(r -> r.bucketSeconds() == MetricRow.HOUR).findFirst().orElseThrow();
        assertThat(hour.bucketStart()).isEqualTo(Instant.parse("2026-10-18T14:00:00Z").getEpochSecond());
        assertThat(aggregator.drain()).isEmpty();
    }

    @Test
    void requestWithoutRouteDoesNotFallBackToTheRawUri() {
        aggregator.onEntry(request(404, 2000, null, "/wp-login.php", Set.of(Entry.Tags.SLOW)));

        assertThat(row(minuteRows(), Metric.SLOW_REQUEST, "GET " + MetricsAggregator.NO_ROUTE).sample())
                .isEqualTo("/wp-login.php");
    }

    @Test
    void slowQueriesAreKeyedByTheirShapeAndFastOnesIgnored() {
        aggregator.onEntry(entry(EntryType.QUERY, Map.of(Entry.Content.SQL, "select * from car where id = 7",
                Entry.Content.DURATION_MS, 300L), Set.of(Entry.Tags.SLOW)));
        aggregator.onEntry(entry(EntryType.QUERY, Map.of(Entry.Content.SQL, "select * from car where id = 8",
                Entry.Content.DURATION_MS, 500L), Set.of(Entry.Tags.SLOW)));
        aggregator.onEntry(entry(EntryType.QUERY, Map.of(Entry.Content.SQL, "select 1",
                Entry.Content.DURATION_MS, 1L), Set.of()));

        List<MetricRow> rows = minuteRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).key()).isEqualTo("select * from car where id = ?");
        assertThat(rows.get(0).hits()).isEqualTo(2);
        assertThat(rows.get(0).peak()).isEqualTo(500);
    }

    @Test
    void exceptionsAndFailedJobsKeepTheLatestDetail() {
        aggregator.onEntry(entry(EntryType.EXCEPTION, Map.of(Entry.Content.EXCEPTION_CLASS, "java.lang.IllegalStateException",
                Entry.Content.LOCATION, "com.acme.Orders.place(Orders.java:42)", Entry.Content.MESSAGE, "first"), Set.of()));
        aggregator.onEntry(entry(EntryType.EXCEPTION, Map.of(Entry.Content.EXCEPTION_CLASS, "java.lang.IllegalStateException",
                Entry.Content.LOCATION, "com.acme.Orders.place(Orders.java:42)", Entry.Content.MESSAGE, "second"), Set.of()));
        aggregator.onEntry(entry(EntryType.SCHEDULED, Map.of(Entry.Content.TASK, "com.acme.Cleanup#purge",
                Entry.Content.DURATION_MS, 20L, Entry.Content.SUCCESS, false, Entry.Content.ERROR, "disk full"), Set.of()));

        List<MetricRow> rows = minuteRows();
        MetricRow exception = row(rows, Metric.EXCEPTION,
                "java.lang.IllegalStateException @ com.acme.Orders.place(Orders.java:42)");
        assertThat(exception.hits()).isEqualTo(2);
        assertThat(exception.sample()).isEqualTo("second");
        assertThat(row(rows, Metric.JOB, "com.acme.Cleanup#purge").hits()).isEqualTo(1);
        assertThat(row(rows, Metric.FAILED_JOB, "com.acme.Cleanup#purge").sample()).isEqualTo("disk full");
    }

    @Test
    void slowOutgoingCallsAreKeyedByOriginAndNeverKeepTheQueryString() {
        aggregator.onEntry(entry(EntryType.HTTP_CLIENT, Map.of(Entry.Content.METHOD, "GET",
                Entry.Content.URL, "https://api.example.com:8443/v1/rates?apiKey=secret",
                Entry.Content.DURATION_MS, 2500L), Set.of(Entry.Tags.SLOW)));

        MetricRow row = row(minuteRows(), Metric.SLOW_OUTGOING, "GET https://api.example.com:8443");
        assertThat(row.sample()).isEqualTo("https://api.example.com:8443/v1/rates");
    }

    @Test
    void restoreAddsFailedRowsToWhatArrivedSince() {
        aggregator.onEntry(request(200, 10, "/a", "/a", Set.of()));
        List<MetricRow> failed = aggregator.drain();
        aggregator.onEntry(request(200, 30, "/a", "/a", Set.of()));

        aggregator.restore(failed);

        MetricRow ok = row(minuteRows(), Metric.THROUGHPUT, "2xx");
        assertThat(ok.hits()).isEqualTo(2);
        assertThat(ok.total()).isEqualTo(40);
        assertThat(ok.peak()).isEqualTo(30);
    }

    @Test
    void newKeysBeyondTheLimitAreDroppedAndCounted() {
        MetricsAggregator small = new MetricsAggregator(2);
        small.add(Metric.JOB, "a", 1, AT, null);
        small.add(Metric.JOB, "b", 1, AT, null);
        small.add(Metric.JOB, "a", 1, AT, null);

        // "a" takes both slots, one per bucket size; "b" is dropped from both
        assertThat(small.pendingSlots()).isEqualTo(2);
        assertThat(small.dropped()).isEqualTo(2);
        assertThat(small.drain()).allMatch(r -> r.key().equals("a"));
    }

    private List<MetricRow> minuteRows() {
        return aggregator.drain().stream()
                .filter(r -> r.bucketSeconds() == MetricRow.MINUTE)
                .sorted(Comparator.comparing(MetricRow::key))
                .toList();
    }

    private static MetricRow row(List<MetricRow> rows, Metric metric, String key) {
        return rows.stream().filter(r -> r.metric() == metric && r.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(metric + " " + key + " not in " + rows));
    }

    private static Entry request(int status, long durationMs, String route, String uri, Set<String> tags) {
        Map<String, Object> content = new java.util.HashMap<>(Map.of(Entry.Content.METHOD, "GET",
                Entry.Content.URI, uri, Entry.Content.STATUS, status, Entry.Content.DURATION_MS, durationMs));
        if (route != null) {
            content.put(Entry.Content.ROUTE, route);
        }
        return entry(EntryType.REQUEST, content, tags);
    }

    private static Entry entry(EntryType type, Map<String, Object> content, Set<String> tags) {
        return new Entry(1, null, type, AT, content, tags);
    }
}
