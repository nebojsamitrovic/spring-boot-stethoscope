package io.github.nebojsamitrovic.stethoscope.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for watchers. Turns raw observations into {@link Entry entries}, attaches them to the
 * current {@link Batch} and hands them to the {@link EntryStore}.
 *
 * <p>Recording must never break the application, so the {@code record*} methods swallow their own
 * failures.
 */
public class Recorder {

    private static final String[] FRAMEWORK_PACKAGES = {
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.hibernate.", "org.h2.", "org.postgresql.",
            "com.mysql.", "com.zaxxer.", "net.ttddyy.", "io.micrometer.", "org.eclipse.jetty.",
            "io.undertow.", "kotlin.", "io.github.nebojsamitrovic.stethoscope.autoconfigure."
    };

    private final EntryStore store;
    private final RecorderSettings settings;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean paused;
    /** Throwables already recorded. Weak keys; Throwable uses identity equality. */
    private final Map<Throwable, Boolean> recentExceptions = new java.util.WeakHashMap<>();

    public Recorder(EntryStore store, RecorderSettings settings) {
        this(store, settings, Clock.systemUTC());
    }

    public Recorder(EntryStore store, RecorderSettings settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EntryStore store() {
        return store;
    }

    public RecorderSettings settings() {
        return settings;
    }

    /** Recording can be paused from the UI without restarting the app. */
    public boolean isRecording() {
        return !paused;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    /** Low-level: store an entry in the current batch. Returns {@code null} when paused. */
    public Entry record(EntryType type, Map<String, Object> content, Set<String> tags) {
        Batch batch = BatchContext.current();
        return record(type, content, tags, batch == null ? null : batch.id());
    }

    /**
     * Low-level: store an entry in an explicit batch, for work that finishes on another thread than
     * the one that started it (e.g. reactive HTTP calls). Returns {@code null} when paused.
     */
    public Entry record(EntryType type, Map<String, Object> content, Set<String> tags, String batchId) {
        if (paused) {
            return null;
        }
        Entry entry = new Entry(
                sequence.incrementAndGet(),
                batchId,
                type,
                Instant.now(clock),
                content,
                tags);
        store.store(entry);
        return entry;
    }

    /**
     * Records an executed SQL statement and updates batch statistics.
     *
     * @param sql            statement as sent to the driver
     * @param parameters     bound parameters in order, may be empty
     * @param durationNanos  execution time
     * @param success        whether the statement completed without throwing
     * @param dataSource     data source name, may be {@code null}
     */
    public void recordQuery(String sql, List<?> parameters, long durationNanos, boolean success, String dataSource) {
        if (paused) {
            return;
        }
        try {
            Batch batch = BatchContext.current();
            if (batch != null) {
                batch.addQuery(SqlNormalizer.normalize(sql), durationNanos);
            }

            long durationMs = durationNanos / 1_000_000;
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.SQL, sql);
            if (settings.recordQueryParameters() && parameters != null && !parameters.isEmpty()) {
                List<String> rendered = new ArrayList<>(parameters.size());
                for (Object parameter : parameters) {
                    rendered.add(renderParameter(parameter));
                }
                content.put(Entry.Content.PARAMETERS, rendered);
            }
            content.put(Entry.Content.DURATION_MS, durationMs);
            content.put(Entry.Content.SUCCESS, success);
            if (dataSource != null) {
                content.put(Entry.Content.DATA_SOURCE, dataSource);
            }

            Set<String> tags = new HashSet<>();
            if (durationMs >= settings.slowQueryMillis()) {
                tags.add(Entry.Tags.SLOW);
            }
            if (!success) {
                tags.add(Entry.Tags.FAILED);
            }
            record(EntryType.QUERY, content, tags);
        } catch (RuntimeException ignored) {
            // never break the application because of the debugger
        }
    }

    /**
     * Records an exception once per batch, even if several watchers report the same instance
     * (e.g. a {@code HandlerExceptionResolver} and then the servlet filter).
     *
     * @param handled {@code true} if the application turned it into a response itself
     */
    public void recordException(Throwable throwable, boolean handled) {
        if (paused || throwable == null) {
            return;
        }
        try {
            // Dedupe per recorder, not per batch: the batch is a thread-local shared by every
            // application context in the JVM, each with its own recorder.
            Batch batch = BatchContext.current();
            if (batch != null) {
                batch.markExceptionRecorded(throwable);
            }
            if (!markRecorded(throwable)) {
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.EXCEPTION_CLASS, throwable.getClass().getName());
            content.put(Entry.Content.MESSAGE, throwable.getMessage());
            String location = applicationLocation(throwable);
            if (location != null) {
                content.put(Entry.Content.LOCATION, location);
            }
            content.put(Entry.Content.HANDLED, handled);
            content.put(Entry.Content.STACK_TRACE, stackTrace(throwable, settings.maxStackTraceFrames()));
            record(EntryType.EXCEPTION, content, Set.of(handled ? "handled" : "unhandled"));
        } catch (RuntimeException ignored) {
            // never break the application because of the debugger
        }
    }

    /**
     * Records values passed to {@link Stethoscope#dump}.
     *
     * @param values   rendered values
     * @param location caller, e.g. {@code com.acme.OrderService.place(OrderService.java:42)}
     */
    public void recordDump(List<String> values, String location) {
        if (paused) {
            return;
        }
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.VALUES, List.copyOf(values));
            if (location != null) {
                content.put(Entry.Content.LOCATION, location);
            }
            record(EntryType.DUMP, content, Set.of());
        } catch (RuntimeException ignored) {
            // never break the application because of the debugger
        }
    }

    /**
     * The same exception is often reported more than once: logged by the code that caught it, then
     * rethrown and seen by the request filter, or wrapped in a {@code ServletException}. Only the
     * first report of anything in a cause chain is kept.
     */
    private boolean markRecorded(Throwable throwable) {
        synchronized (recentExceptions) {
            Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Throwable t = throwable; t != null && seen.add(t); t = t.getCause()) {
                if (recentExceptions.containsKey(t)) {
                    return false;
                }
            }
            recentExceptions.put(throwable, Boolean.TRUE);
            return true;
        }
    }

    /**
     * Builds the request-summary tags and statistics from the batch, so the request entry
     * shows "14 queries, 230 ms, N+1".
     */
    public void applyBatchSummary(Batch batch, Map<String, Object> content, Set<String> tags) {
        if (batch == null) {
            return;
        }
        content.put(Entry.Content.QUERY_COUNT, batch.queryCount());
        content.put(Entry.Content.QUERY_TIME_MS, batch.queryTimeMillis());
        Map<String, Integer> repeated = batch.repeatedStatements(settings.duplicateQueryThreshold());
        if (!repeated.isEmpty()) {
            content.put(Entry.Content.DUPLICATE_QUERIES, repeated);
            tags.add(Entry.Tags.N_PLUS_ONE);
        }
        if (batch.hasExceptions()) {
            tags.add(Entry.Tags.HAS_EXCEPTION);
        }
    }

    /** First stack frame that belongs to the application rather than a framework, e.g. {@code OrderService.java:42}. */
    static String applicationLocation(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                if (!isFrameworkFrame(frame.getClassName())) {
                    return frame.getClassName() + "." + frame.getMethodName()
                            + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    static String stackTrace(Throwable throwable, int maxFrames) {
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable current = throwable;
        boolean first = true;
        while (current != null && seen.add(current)) {
            if (!first) {
                out.append("Caused by: ");
            }
            out.append(current).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            int shown = Math.min(frames.length, maxFrames);
            for (int i = 0; i < shown; i++) {
                out.append("    at ").append(frames[i]).append('\n');
            }
            if (frames.length > shown) {
                out.append("    ... ").append(frames.length - shown).append(" more\n");
            }
            current = current.getCause();
            first = false;
        }
        return out.toString();
    }

    private static boolean isFrameworkFrame(String className) {
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        // generated proxies, lambdas and reflection accessors are noise as well
        return className.contains("$$") || className.contains("$Proxy") || className.startsWith("jdk.internal.");
    }

    private static String renderParameter(Object parameter) {
        if (parameter == null) {
            return "null";
        }
        if (parameter instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        String text = String.valueOf(parameter);
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
