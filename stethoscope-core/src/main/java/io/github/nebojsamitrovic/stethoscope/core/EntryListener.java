package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Sees every entry the {@link Recorder} creates, right after it was handed to the {@link EntryStore}.
 * Used for what is derived from entries rather than kept as they are, such as the overview metrics.
 *
 * <p>Called on the thread that recorded the entry, so implementations must be fast and thread-safe.
 * A listener that throws is ignored for that entry; it never breaks recording or the application.
 */
@FunctionalInterface
public interface EntryListener {

    void onEntry(Entry entry);
}
