package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcMetricsStoreTest {

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource("jdbc:h2:mem:metrics-store;DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcMetricsStore store = new JdbcMetricsStore(dataSource, true);

    @BeforeEach
    void setUp() {
        store.initialize();
        store.initialize();
        new JdbcTemplate(dataSource).update("delete from " + JdbcMetricsStore.TABLE);
    }

    @Test
    void writingTheSameBucketTwiceAddsUp() {
        store.write(List.of(row(600, Metric.SLOW_QUERY, "select ?", 2, 300, 200, 610, null)));
        store.write(List.of(row(600, Metric.SLOW_QUERY, "select ?", 1, 900, 900, 650, null)));

        MetricRow stored = store.series(Metric.SLOW_QUERY, MetricRow.MINUTE, 0).get(0);
        assertThat(stored.hits()).isEqualTo(3);
        assertThat(stored.total()).isEqualTo(1200);
        assertThat(stored.peak()).isEqualTo(900);
        assertThat(stored.lastSeen()).isEqualTo(650);
    }

    @Test
    void anOlderWriteDoesNotReplaceANewerSample() {
        store.write(List.of(row(600, Metric.EXCEPTION, "Boom", 1, 0, 0, 650, "newer")));
        store.write(List.of(row(600, Metric.EXCEPTION, "Boom", 1, 0, 0, 620, "older")));
        store.write(List.of(row(600, Metric.EXCEPTION, "Boom", 1, 0, 0, 660, null)));

        assertThat(store.series(Metric.EXCEPTION, MetricRow.MINUTE, 0).get(0).sample()).isEqualTo("newer");
    }

    @Test
    void topSumsBucketsAndTakesTheLatestSample() {
        store.write(List.of(
                row(600, Metric.EXCEPTION, "A", 5, 0, 0, 610, "a-old"),
                row(660, Metric.EXCEPTION, "A", 1, 0, 0, 700, "a-new"),
                row(660, Metric.EXCEPTION, "B", 3, 0, 0, 690, "b"),
                row(60, Metric.EXCEPTION, "C", 99, 0, 0, 90, "before the period")));

        List<MetricRow> top = store.top(Metric.EXCEPTION, MetricRow.MINUTE, 600, MetricsStore.Order.HITS, 10);

        assertThat(top).extracting(MetricRow::key).containsExactly("A", "B");
        assertThat(top.get(0).hits()).isEqualTo(6);
        assertThat(top.get(0).sample()).isEqualTo("a-new");
        assertThat(top.get(0).lastSeen()).isEqualTo(700);
        assertThat(store.top(Metric.EXCEPTION, MetricRow.MINUTE, 600, MetricsStore.Order.HITS, 1)).hasSize(1);
    }

    @Test
    void topByPeakPutsTheSlowestFirst() {
        store.write(List.of(
                row(600, Metric.JOB, "often", 50, 500, 20, 610, null),
                row(600, Metric.JOB, "slow", 1, 4000, 4000, 610, null)));

        assertThat(store.top(Metric.JOB, MetricRow.MINUTE, 0, MetricsStore.Order.PEAK, 10))
                .extracting(MetricRow::key).containsExactly("slow", "often");
    }

    @Test
    void longKeysAreStoredWhole() {
        String sql = "select " + "x, ".repeat(400) + "y from t";
        store.write(List.of(row(600, Metric.SLOW_QUERY, sql.substring(0, 1000), 1, 1, 1, 600, null)));

        assertThat(store.top(Metric.SLOW_QUERY, MetricRow.MINUTE, 0, MetricsStore.Order.HITS, 1).get(0).key())
                .hasSize(1000);
    }

    @Test
    void purgeDeletesOnlyOldBucketsOfThatSize() {
        store.write(List.of(
                row(60, Metric.JOB, "a", 1, 1, 1, 60, null),
                row(600, Metric.JOB, "a", 1, 1, 1, 600, null),
                new MetricRow(MetricRow.HOUR, 0, Metric.JOB, "a", 1, 1, 1, 60, null)));

        assertThat(store.purge(MetricRow.MINUTE, 600)).isEqualTo(1);
        assertThat(store.series(Metric.JOB, MetricRow.MINUTE, 0)).extracting(MetricRow::bucketStart).containsExactly(600L);
        assertThat(store.series(Metric.JOB, MetricRow.HOUR, 0)).hasSize(1);
    }

    private static MetricRow row(long bucketStart, Metric metric, String key, long hits, long total, long peak,
            long lastSeen, String sample) {
        return new MetricRow(MetricRow.MINUTE, bucketStart, metric, key, hits, total, peak, lastSeen, sample);
    }
}
