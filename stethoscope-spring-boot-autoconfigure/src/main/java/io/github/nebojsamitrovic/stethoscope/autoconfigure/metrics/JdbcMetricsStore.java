package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps metrics rows in one table of the application's database ({@code stethoscope_metrics}, see
 * {@code META-INF/stethoscope/metrics-schema.sql}).
 *
 * <p>Writes are update-then-insert in one transaction rather than a vendor upsert, so the same SQL
 * runs on PostgreSQL, MySQL and H2. Two instances inserting the same new row at once make one
 * transaction fail; the scheduler puts its rows back and the next write adds them to the row the
 * other instance created.
 *
 * <p>Everything runs {@link Recorder#unrecorded unrecorded}: these statements go through the
 * application's data source, which Stethoscope may have wrapped.
 */
public class JdbcMetricsStore implements MetricsStore {

    static final String TABLE = "stethoscope_metrics";
    private static final String SCHEMA = "META-INF/stethoscope/metrics-schema.sql";

    private static final String UPDATE = "UPDATE " + TABLE + " SET hits = hits + ?, total = total + ?,"
            + " peak = CASE WHEN peak < ? THEN ? ELSE peak END,"
            + " sample = CASE WHEN last_seen <= ? AND ? IS NOT NULL THEN ? ELSE sample END,"
            + " last_seen = CASE WHEN last_seen < ? THEN ? ELSE last_seen END"
            + " WHERE bucket_seconds = ? AND bucket_start = ? AND metric = ? AND key_hash = ?";

    private static final String INSERT = "INSERT INTO " + TABLE + " (bucket_seconds, bucket_start, metric, key_hash,"
            + " metric_key, hits, total, peak, last_seen, sample) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String TOP = "SELECT key_hash, MAX(metric_key) AS any_key, SUM(hits) AS sum_hits,"
            + " SUM(total) AS sum_total, MAX(peak) AS max_peak, MAX(last_seen) AS max_seen FROM " + TABLE
            + " WHERE bucket_seconds = ? AND metric = ? AND bucket_start >= ? GROUP BY key_hash";

    private static final String LATEST_SAMPLE = "SELECT sample FROM " + TABLE
            + " WHERE bucket_seconds = ? AND metric = ? AND key_hash = ? AND bucket_start >= ? AND sample IS NOT NULL"
            + " ORDER BY bucket_start DESC";

    private static final String SERIES = "SELECT bucket_start, metric_key, hits, total, peak, last_seen, sample FROM "
            + TABLE + " WHERE bucket_seconds = ? AND metric = ? AND bucket_start >= ? ORDER BY bucket_start";

    private static final String PURGE = "DELETE FROM " + TABLE + " WHERE bucket_seconds = ? AND bucket_start < ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean initializeSchema;

    /** @param initializeSchema run {@code CREATE TABLE IF NOT EXISTS} in {@link #initialize()} */
    public JdbcMetricsStore(DataSource dataSource, boolean initializeSchema) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.initializeSchema = initializeSchema;
    }

    @Override
    public void initialize() {
        if (initializeSchema) {
            Recorder.unrecorded(() -> {
                jdbc.execute(schema());
                return null;
            });
        }
    }

    @Override
    public void write(List<MetricRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Recorder.unrecorded(() -> transaction.execute(status -> {
            for (MetricRow row : rows) {
                String hash = hash(row.key());
                SqlParameterValue sample = new SqlParameterValue(Types.VARCHAR, row.sample());
                int updated = jdbc.update(UPDATE,
                        row.hits(), row.total(),
                        row.peak(), row.peak(),
                        row.lastSeen(), sample, sample,
                        row.lastSeen(), row.lastSeen(),
                        row.bucketSeconds(), row.bucketStart(), row.metric().id(), hash);
                if (updated == 0) {
                    jdbc.update(INSERT,
                            row.bucketSeconds(), row.bucketStart(), row.metric().id(), hash,
                            row.key(), row.hits(), row.total(), row.peak(), row.lastSeen(), sample);
                }
            }
            return null;
        }));
    }

    @Override
    public List<MetricRow> top(Metric metric, int bucketSeconds, long from, Order order, int limit) {
        String sql = TOP + (order == Order.PEAK ? " ORDER BY max_peak DESC" : " ORDER BY sum_hits DESC") + ", key_hash";
        return Recorder.unrecorded(() -> {
            List<Summed> summed = query(sql, limit, rs -> new Summed(rs.getString("key_hash"), rs.getString("any_key"),
                    rs.getLong("sum_hits"), rs.getLong("sum_total"), rs.getLong("max_peak"), rs.getLong("max_seen")),
                    bucketSeconds, metric.id(), from);
            List<MetricRow> result = new ArrayList<>(summed.size());
            for (Summed s : summed) {
                List<String> samples = query(LATEST_SAMPLE, 1, rs -> rs.getString(1),
                        bucketSeconds, metric.id(), s.hash(), from);
                result.add(new MetricRow(bucketSeconds, from, metric, s.key(), s.hits(), s.total(), s.peak(),
                        s.lastSeen(), samples.isEmpty() ? null : samples.get(0)));
            }
            return result;
        });
    }

    private record Summed(String hash, String key, long hits, long total, long peak, long lastSeen) {
    }

    @Override
    public List<MetricRow> series(Metric metric, int bucketSeconds, long from) {
        return Recorder.unrecorded(() -> jdbc.query(SERIES, (rs, n) -> new MetricRow(bucketSeconds,
                rs.getLong("bucket_start"), metric, rs.getString("metric_key"), rs.getLong("hits"),
                rs.getLong("total"), rs.getLong("peak"), rs.getLong("last_seen"), rs.getString("sample")),
                bucketSeconds, metric.id(), from));
    }

    @Override
    public int purge(int bucketSeconds, long before) {
        return Recorder.unrecorded(() -> jdbc.update(PURGE, bucketSeconds, before));
    }

    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** A query capped at {@code maxRows} on the statement; {@code LIMIT} is not portable. */
    private <T> List<T> query(String sql, int maxRows, RowReader<T> reader, Object... args) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(maxRows);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, rs -> {
            List<T> out = new ArrayList<>();
            while (rs.next() && out.size() < maxRows) {
                out.add(reader.read(rs));
            }
            return out;
        });
    }

    /** Fixed-width primary key column for keys too long to index, such as SQL statements. */
    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is required of every JRE", ex);
        }
    }

    private static String schema() {
        try (InputStream in = JdbcMetricsStore.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Stethoscope metrics schema missing from the jar: " + SCHEMA);
            }
            StringBuilder sql = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().startsWith("--")) {
                    sql.append(line).append('\n');
                }
            }
            return sql.toString().trim();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
