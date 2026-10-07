package io.github.nebojsamitrovic.stethoscope.autoconfigure.jobs;

import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Wraps tasks handed to an executor so each run becomes a {@link EntryType#JOB} entry with its own
 * {@link Batch}, linked to the batch that submitted it (usually the HTTP request).
 */
public final class RecordingTasks {

    private RecordingTasks() {
    }

    /** Wraps {@link Runnable}s and {@link Callable}s; anything else is returned unchanged. */
    static Object wrap(Object task, String executor, Supplier<Recorder> recorder) {
        if (task instanceof Job) {
            return task;
        }
        Recorder target = recorder.get();
        if (target == null || !target.isRecording()) {
            return task;
        }
        Batch parent = BatchContext.current();
        Submission submission = new Submission(name(task), executor, parent == null ? null : parent.id(), System.nanoTime(), recorder);
        if (task instanceof Callable<?> callable) {
            return new JobCallable<>(callable, submission);
        }
        if (task instanceof Runnable runnable) {
            return new JobRunnable(runnable, submission);
        }
        return task;
    }

    /** Marker so a task is never wrapped twice. */
    interface Job {
    }

    private record Submission(String name, String executor, String parentBatch, long submittedNanos,
            Supplier<Recorder> recorder) {

        <T> T run(Callable<T> task) throws Exception {
            Recorder target = recorder.get();
            if (target == null || !target.isRecording()) {
                return task.call();
            }
            Batch previous = BatchContext.current();
            Batch batch = BatchContext.start();
            long start = System.nanoTime();
            Throwable error = null;
            try {
                return task.call();
            } catch (Exception | Error ex) {
                error = ex;
                throw ex;
            } finally {
                record(target, batch, start, error);
                BatchContext.restore(previous);
            }
        }

        private void record(Recorder target, Batch batch, long start, Throwable error) {
            try {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put(Entry.Content.TASK, name);
                content.put(Entry.Content.DURATION_MS, (System.nanoTime() - start) / 1_000_000);
                content.put(Entry.Content.WAIT_MS, (start - submittedNanos) / 1_000_000);
                content.put(Entry.Content.SUCCESS, error == null);
                content.put(Entry.Content.THREAD, Thread.currentThread().getName());
                if (executor != null) {
                    content.put(Entry.Content.EXECUTOR, executor);
                }
                if (parentBatch != null) {
                    content.put(Entry.Content.PARENT_BATCH, parentBatch);
                }
                Set<String> tags = new HashSet<>();
                if (error != null) {
                    content.put(Entry.Content.ERROR, error.getClass().getName() + ": " + error.getMessage());
                    tags.add(Entry.Tags.FAILED);
                    target.recordException(error, false);
                }
                target.applyBatchSummary(batch, content, tags);
                target.record(EntryType.JOB, content, tags);
            } catch (RuntimeException ignored) {
                // never break the task because of the debugger
            }
        }
    }

    static final class JobRunnable implements Runnable, Job {

        private final Runnable delegate;
        private final Submission submission;

        JobRunnable(Runnable delegate, Submission submission) {
            this.delegate = delegate;
            this.submission = submission;
        }

        @Override
        public void run() {
            try {
                submission.run(() -> {
                    delegate.run();
                    return null;
                });
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Exception ex) {
                // a Runnable cannot throw checked exceptions
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    static final class JobCallable<T> implements Callable<T>, Job {

        private final Callable<T> delegate;
        private final Submission submission;

        JobCallable(Callable<T> delegate, Submission submission) {
            this.delegate = delegate;
            this.submission = submission;
        }

        @Override
        public T call() throws Exception {
            return submission.run(delegate);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * Best-effort task name. For {@code @Async} methods Spring submits a lambda that captures the
     * target {@link Method}, which gives {@code OrderService#sendConfirmation}; otherwise the task's
     * class (or the class that declared the lambda).
     */
    static String name(Object task) {
        Class<?> type = task.getClass();
        String className = type.getName();
        int lambda = className.indexOf("$$Lambda");
        if (lambda < 0) {
            return className;
        }
        try {
            for (Field field : type.getDeclaredFields()) {
                if (Method.class.equals(field.getType())) {
                    field.setAccessible(true);
                    if (field.get(task) instanceof Method method) {
                        return method.getDeclaringClass().getName() + "#" + method.getName();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall back to the declaring class
        }
        return className.substring(0, lambda) + " (lambda)";
    }
}
