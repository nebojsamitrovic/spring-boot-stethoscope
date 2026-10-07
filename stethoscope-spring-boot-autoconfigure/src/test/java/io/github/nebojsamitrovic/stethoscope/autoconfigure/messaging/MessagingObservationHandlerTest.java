package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryQuery;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.InMemoryEntryStore;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.RecorderSettings;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

class MessagingObservationHandlerTest {

    private final InMemoryEntryStore store = new InMemoryEntryStore(100);
    private final Recorder recorder = new Recorder(store, RecorderSettings.defaults());
    private final MessagingObservationHandler handler =
            new MessagingObservationHandler(() -> recorder, Redactor::defaults);

    @AfterEach
    void tearDown() {
        BatchContext.end();
    }

    @Test
    void kafkaListenerInvocationIsItsOwnBatch() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("orders", 2, 41L, "order-7", "{\"id\":7,\"password\":\"test\"}");
        KafkaRecordReceiverContext context = new KafkaRecordReceiverContext(record, "orderListener", () -> "cluster-1");
        assertThat(handler.supportsContext(context)).isTrue();

        handler.onStart(context);
        recorder.recordQuery("insert into orders values (?)", List.of(7), 1_000_000, true, null);
        context.setError(new IllegalStateException("duplicate order"));
        handler.onStop(context);

        assertThat(BatchContext.current()).isNull();
        Entry message = store.list(EntryQuery.of(EntryType.MESSAGE)).get(0);
        assertThat(message.getString(Entry.Content.SYSTEM, "")).isEqualTo("kafka");
        assertThat(message.getString(Entry.Content.DIRECTION, "")).isEqualTo("received");
        assertThat(message.getString(Entry.Content.DESTINATION, "")).isEqualTo("orders");
        assertThat(message.getString(Entry.Content.KEY, "")).isEqualTo("order-7");
        assertThat(message.getString(Entry.Content.LISTENER, "")).isEqualTo("orderListener");
        assertThat(message.getString(Entry.Content.PAYLOAD, "")).contains("\"id\":7").contains("\"password\":\"" + Redactor.MASK + "\"");
        assertThat(message.<Map<String, String>>get(Entry.Content.METADATA)).containsEntry("partition", "2").containsEntry("offset", "41");
        assertThat(message.<Boolean>get(Entry.Content.SUCCESS)).isFalse();
        assertThat(message.<Integer>get(Entry.Content.QUERY_COUNT)).isEqualTo(1);

        assertThat(store.batch(message.batchId())).extracting(Entry::type)
                .containsExactlyInAnyOrder(EntryType.QUERY, EntryType.EXCEPTION, EntryType.MESSAGE);
    }

    @Test
    void rabbitSendIsLinkedToTheCurrentBatch() {
        Batch request = BatchContext.start();
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message body = new Message("{\"total\":42}".getBytes(StandardCharsets.UTF_8), properties);
        RabbitMessageSenderContext context = new RabbitMessageSenderContext(body, "rabbitTemplate", "shop", "order.created");

        handler.onStart(context);
        handler.onStop(context);

        assertThat(BatchContext.current()).isSameAs(request);
        Entry message = store.list(EntryQuery.of(EntryType.MESSAGE)).get(0);
        assertThat(message.batchId()).isEqualTo(request.id());
        assertThat(message.getString(Entry.Content.DIRECTION, "")).isEqualTo("sent");
        assertThat(message.getString(Entry.Content.DESTINATION, "")).isEqualTo("shop / order.created");
        assertThat(message.getString(Entry.Content.PAYLOAD, "")).isEqualTo("{\"total\":42}");
        assertThat(message.hasTag("rabbitmq")).isTrue();
    }

    @Test
    void binaryPayloadsAreNotDecoded() {
        assertThat(Payloads.text(new byte[] {(byte) 0xC3, (byte) 0x28})).isEqualTo("<2 bytes>");
        assertThat(Payloads.text("plain".getBytes(StandardCharsets.UTF_8))).isEqualTo("plain");
    }
}
