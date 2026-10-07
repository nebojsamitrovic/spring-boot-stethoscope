package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.util.ClassUtils;

/**
 * Records Kafka and RabbitMQ messages from the observations spring-kafka and spring-rabbit emit.
 *
 * <p>A received message is handled like a request: the listener invocation gets its own
 * {@link Batch}, so queries, logs and exceptions inside the listener are linked to it. A sent message
 * is linked to whatever batch sent it. Kafka completes sends on the producer thread, so the batch is
 * captured when the send starts.
 */
public class MessagingObservationHandler implements ObservationHandler<Observation.Context> {

    static final String RECEIVED = "received";
    static final String SENT = "sent";

    private static final String STATE = MessagingObservationHandler.class.getName() + ".state";

    private final Supplier<Recorder> recorder;
    private final Supplier<Redactor> redactor;
    private final List<MessageDescription.Adapter> adapters;

    /** Suppliers, since the handler is created by a BeanPostProcessor before the beans it uses exist. */
    public MessagingObservationHandler(Supplier<Recorder> recorder, Supplier<Redactor> redactor) {
        this(recorder, redactor, defaultAdapters());
    }

    MessagingObservationHandler(Supplier<Recorder> recorder, Supplier<Redactor> redactor, List<MessageDescription.Adapter> adapters) {
        this.recorder = recorder;
        this.redactor = redactor;
        this.adapters = List.copyOf(adapters);
    }

    private static List<MessageDescription.Adapter> defaultAdapters() {
        List<MessageDescription.Adapter> adapters = new ArrayList<>();
        ClassLoader loader = MessagingObservationHandler.class.getClassLoader();
        if (ClassUtils.isPresent("org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext", loader)) {
            adapters.add(new KafkaAdapter());
        }
        if (ClassUtils.isPresent("org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext", loader)) {
            adapters.add(new RabbitAdapter());
        }
        return adapters;
    }

    private record State(MessageDescription message, Batch batch, Batch previous, String batchId, long startNanos) {
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ReceiverContext<?> || context instanceof SenderContext<?>;
    }

    @Override
    public void onStart(Observation.Context context) {
        try {
            Recorder target = recorder.get();
            if (target == null || !target.isRecording()) {
                return;
            }
            MessageDescription message = describe(context);
            if (message == null) {
                return;
            }
            if (message.received()) {
                Batch previous = BatchContext.current();
                Batch batch = BatchContext.start();
                context.put(STATE, new State(message, batch, previous, batch.id(), System.nanoTime()));
            } else {
                Batch current = BatchContext.current();
                context.put(STATE, new State(message, null, null, current == null ? null : current.id(), System.nanoTime()));
            }
        } catch (RuntimeException ignored) {
            // never break messaging because of the debugger
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        State state = context.get(STATE);
        if (state == null) {
            return;
        }
        try {
            Recorder target = recorder.get();
            if (target != null) {
                record(target, state, context.getError());
            }
        } catch (RuntimeException ignored) {
            // never break messaging because of the debugger
        } finally {
            if (state.batch() != null) {
                BatchContext.restore(state.previous());
            }
        }
    }

    private void record(Recorder target, State state, Throwable error) {
        MessageDescription message = state.message();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(Entry.Content.SYSTEM, message.system());
        content.put(Entry.Content.DIRECTION, message.received() ? RECEIVED : SENT);
        content.put(Entry.Content.DESTINATION, message.destination());
        if (message.key() != null) {
            content.put(Entry.Content.KEY, message.key());
        }
        if (message.listener() != null) {
            content.put(Entry.Content.LISTENER, message.listener());
        }
        content.put(Entry.Content.DURATION_MS, (System.nanoTime() - state.startNanos()) / 1_000_000);
        content.put(Entry.Content.SUCCESS, error == null);
        if (message.payload() != null) {
            Redactor masking = redactor.get();
            content.put(Entry.Content.PAYLOAD, masking == null ? message.payload() : masking.body(message.payload()));
        }
        if (!message.metadata().isEmpty()) {
            content.put(Entry.Content.METADATA, message.metadata());
        }
        Set<String> tags = new HashSet<>(Set.of(message.system(), message.received() ? RECEIVED : SENT));
        if (error != null) {
            content.put(Entry.Content.ERROR, error.getClass().getName() + ": " + error.getMessage());
            tags.add(Entry.Tags.FAILED);
            if (message.received()) {
                target.recordException(error, false);
            }
        }
        if (state.batch() != null) {
            target.applyBatchSummary(state.batch(), content, tags);
        }
        target.record(EntryType.MESSAGE, content, tags, state.batchId());
    }

    private MessageDescription describe(Observation.Context context) {
        for (MessageDescription.Adapter adapter : adapters) {
            MessageDescription description = adapter.describe(context);
            if (description != null) {
                return description;
            }
        }
        return null;
    }
}
