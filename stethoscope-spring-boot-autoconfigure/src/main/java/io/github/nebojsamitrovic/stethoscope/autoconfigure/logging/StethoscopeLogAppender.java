package io.github.nebojsamitrovic.stethoscope.autoconfigure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Logback appender that records log events as {@link EntryType#LOG} entries, linked to the current
 * request or scheduled run.
 */
public class StethoscopeLogAppender extends AppenderBase<ILoggingEvent> {

    private static final List<String> OWN_PACKAGES = List.of(
            "io.github.nebojsamitrovic.stethoscope.core.", "io.github.nebojsamitrovic.stethoscope.autoconfigure.");

    /** Guards against recording log events emitted while recording (e.g. by a custom EntryStore). */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private final Recorder recorder;
    private final Level threshold;
    private final List<String> ignoreLoggers;
    private final boolean recordExceptions;

    public StethoscopeLogAppender(Recorder recorder, String level, List<String> ignoreLoggers, boolean recordExceptions) {
        this.recorder = recorder;
        this.threshold = Level.toLevel(level, Level.INFO);
        this.ignoreLoggers = ignoreLoggers == null ? List.of() : List.copyOf(ignoreLoggers);
        this.recordExceptions = recordExceptions;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!recorder.isRecording() || !event.getLevel().isGreaterOrEqual(threshold) || isIgnored(event.getLoggerName())
                || ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.LEVEL, event.getLevel().toString());
            content.put(Entry.Content.LOGGER, event.getLoggerName());
            content.put(Entry.Content.MESSAGE, event.getFormattedMessage());
            content.put(Entry.Content.THREAD, event.getThreadName());
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc != null && !mdc.isEmpty()) {
                content.put(Entry.Content.MDC, new TreeMap<>(mdc));
            }
            IThrowableProxy proxy = event.getThrowableProxy();
            Set<String> tags = new HashSet<>();
            tags.add(event.getLevel().toString().toLowerCase(Locale.ROOT));
            if (proxy != null) {
                content.put(Entry.Content.EXCEPTION_CLASS, proxy.getClassName());
                content.put(Entry.Content.STACK_TRACE, ThrowableProxyUtil.asString(proxy));
                tags.add(Entry.Tags.HAS_EXCEPTION);
            }
            recorder.record(EntryType.LOG, content, tags);

            if (recordExceptions && proxy instanceof ThrowableProxy throwableProxy
                    && event.getLevel().isGreaterOrEqual(Level.ERROR)) {
                recorder.recordException(throwableProxy.getThrowable(), true);
            }
        } catch (RuntimeException ignored) {
            // never break logging because of the debugger
        } finally {
            ACTIVE.remove();
        }
    }

    private boolean isIgnored(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        for (String prefix : OWN_PACKAGES) {
            if (loggerName.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : ignoreLoggers) {
            if (prefix != null && !prefix.isBlank() && loggerName.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }
}
