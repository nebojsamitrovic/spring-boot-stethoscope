package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import io.micrometer.observation.Observation;
import java.util.Map;

/**
 * What the dashboard shows about one message.
 *
 * @param system      {@code kafka} or {@code rabbitmq}
 * @param received    {@code true} for a listener invocation, {@code false} for a send
 * @param destination topic, queue or {@code exchange/routing-key}
 * @param key         message key, may be {@code null}
 * @param listener    listener id (received only), may be {@code null}
 * @param payload     payload preview, may be {@code null}
 * @param metadata    partition, offset, headers... in display order
 */
record MessageDescription(String system, boolean received, String destination, String key, String listener,
        String payload, Map<String, String> metadata) {

    /** Turns one broker library's observation context into a {@link MessageDescription}. */
    interface Adapter {

        /** Returns {@code null} if the context belongs to another library. */
        MessageDescription describe(Observation.Context context);
    }
}
