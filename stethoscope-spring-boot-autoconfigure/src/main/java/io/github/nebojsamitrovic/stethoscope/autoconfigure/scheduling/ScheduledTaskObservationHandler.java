package io.github.nebojsamitrovic.stethoscope.autoconfigure.scheduling;

import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Turns every run of a {@code @Scheduled} method into a {@link EntryType#SCHEDULED} entry. Each run
 * gets its own {@link Batch}, so queries, logs and exceptions inside it are linked to the run the
 * same way they are linked to an HTTP request.
 */
public class ScheduledTaskObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private static final String STATE = ScheduledTaskObservationHandler.class.getName() + ".state";

    private final Recorder recorder;

    public ScheduledTaskObservationHandler(Recorder recorder) {
        this.recorder = recorder;
    }

    private record State(Batch batch, Batch previous, long startNanos) {
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        try {
            if (!recorder.isRecording()) {
                return;
            }
            Batch previous = BatchContext.current();
            Batch batch = BatchContext.start();
            context.put(STATE, new State(batch, previous, System.nanoTime()));
        } catch (RuntimeException ignored) {
            // never break the task because of the debugger
        }
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        State state = context.get(STATE);
        if (state == null) {
            return;
        }
        try {
            long durationMs = (System.nanoTime() - state.startNanos()) / 1_000_000;
            Throwable error = context.getError();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.TASK, taskName(context));
            content.put(Entry.Content.DURATION_MS, durationMs);
            content.put(Entry.Content.SUCCESS, error == null);
            content.put(Entry.Content.THREAD, Thread.currentThread().getName());
            Set<String> tags = new HashSet<>();
            if (error != null) {
                content.put(Entry.Content.ERROR, error.getClass().getName() + ": " + error.getMessage());
                tags.add(Entry.Tags.FAILED);
                recorder.recordException(error, false);
            }
            recorder.applyBatchSummary(state.batch(), content, tags);
            recorder.record(EntryType.SCHEDULED, content, tags);
        } catch (RuntimeException ignored) {
            // never break the task because of the debugger
        } finally {
            BatchContext.end();
            if (state.previous() != null) {
                BatchContext.restore(state.previous());
            }
        }
    }

    private static String taskName(ScheduledTaskObservationContext context) {
        Class<?> target = context.getTargetClass();
        String type = target == null ? "?" : target.getName();
        int cglib = type.indexOf("$$");
        if (cglib > 0) {
            type = type.substring(0, cglib);
        }
        return type + "#" + (context.getMethod() == null ? "?" : context.getMethod().getName());
    }
}
