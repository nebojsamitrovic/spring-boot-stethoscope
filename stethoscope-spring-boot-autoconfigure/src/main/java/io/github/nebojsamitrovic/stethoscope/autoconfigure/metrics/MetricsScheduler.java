package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The metrics' own background thread: writes what the aggregator collected every {@code flushInterval},
 * samples CPU and heap every minute and deletes old buckets every hour. On shutdown it writes once
 * more, before the data source closes.
 *
 * <p>Uses a private executor rather than the application's {@code TaskScheduler}, so none of this
 * shows up among the application's own jobs.
 */
public class MetricsScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MetricsScheduler.class);

    /** Minute buckets back the views up to 24 hours; an hour of slack keeps the oldest bars whole. */
    static final Duration MINUTE_RETENTION = Duration.ofHours(25);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(5);

    private final MetricsAggregator aggregator;
    private final MetricsStore store;
    private final SystemSampler sampler;
    private final Duration flushInterval;
    private final Duration retention;
    private final Clock clock;

    private volatile ScheduledExecutorService executor;
    private volatile boolean initialized;
    private long lastWarning = Long.MIN_VALUE;

    /** @param sampler {@code null} to leave CPU and heap out */
    public MetricsScheduler(MetricsAggregator aggregator, MetricsStore store, SystemSampler sampler,
            Duration flushInterval, Duration retention, Clock clock) {
        this.aggregator = aggregator;
        this.store = store;
        this.sampler = sampler;
        this.flushInterval = flushInterval;
        this.retention = retention;
        this.clock = clock;
    }

    /** Creates the table before the first page view can read it; a failure is retried on the next write. */
    @Override
    public void start() {
        quietly("schema initialization", this::initialize);
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stethoscope-metrics");
            thread.setDaemon(true);
            return thread;
        });
        long flushMillis = Math.max(1000, flushInterval.toMillis());
        service.scheduleWithFixedDelay(() -> quietly("write", this::flush), flushMillis, flushMillis, TimeUnit.MILLISECONDS);
        if (sampler != null) {
            service.scheduleAtFixedRate(() -> quietly("system sample", sampler::sample), 0, 1, TimeUnit.MINUTES);
        }
        service.scheduleWithFixedDelay(() -> quietly("purge", this::purge), 1, 60, TimeUnit.MINUTES);
        executor = service;
    }

    @Override
    public void stop() {
        ScheduledExecutorService service = executor;
        executor = null;
        if (service == null) {
            return;
        }
        service.shutdown();
        try {
            service.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        quietly("write", this::flush);
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** Writes everything collected so far; on failure keeps it for the next attempt. */
    public void flush() {
        initialize();
        List<MetricRow> rows = aggregator.drain();
        try {
            store.write(rows);
        } catch (RuntimeException ex) {
            aggregator.restore(rows);
            throw ex;
        }
    }

    private void initialize() {
        if (!initialized) {
            store.initialize();
            initialized = true;
        }
    }

    public void purge() {
        long now = clock.instant().getEpochSecond();
        store.purge(MetricRow.MINUTE, now - MINUTE_RETENTION.toSeconds());
        store.purge(MetricRow.HOUR, now - retention.toSeconds());
    }

    /**
     * Runs a tick without recording it (its SQL and log lines are not the application's) and without
     * letting a failure end the schedule. A database that is down is reported every few minutes, not
     * every tick.
     */
    private void quietly(String what, Runnable tick) {
        Recorder.unrecorded(() -> {
            try {
                tick.run();
            } catch (RuntimeException ex) {
                long now = clock.millis();
                if (now - lastWarning >= WARNING_INTERVAL.toMillis()) {
                    lastWarning = now;
                    log.warn("Stethoscope metrics {} failed ({} keys waiting, {} occurrences dropped): {}",
                            what, aggregator.pendingSlots(), aggregator.dropped(), ex.toString());
                }
            }
            return null;
        });
    }
}
