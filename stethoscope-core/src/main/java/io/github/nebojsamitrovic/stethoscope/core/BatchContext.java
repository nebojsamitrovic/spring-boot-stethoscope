package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Holds the current thread's {@link Batch}.
 *
 * <p>Work handed off to other threads ({@code @Async}, executors, reactive pipelines) is not part of
 * the batch yet; such entries are still recorded, just without a batch id.
 */
public final class BatchContext {

    private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();

    private BatchContext() {
    }

    /** Starts a new batch on this thread, replacing any existing one. */
    public static Batch start() {
        Batch batch = new Batch();
        CURRENT.set(batch);
        return batch;
    }

    /** The current batch, or {@code null} when the thread is not inside one. */
    public static Batch current() {
        return CURRENT.get();
    }

    public static void end() {
        CURRENT.remove();
    }
}
