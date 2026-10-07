package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import io.micrometer.observation.Observation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;

/** Only loaded when spring-kafka is on the classpath. */
final class KafkaAdapter implements MessageDescription.Adapter {

    @Override
    public MessageDescription describe(Observation.Context context) {
        if (context instanceof KafkaRecordReceiverContext receiver) {
            ConsumerRecord<?, ?> record = receiver.getRecord();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("partition", String.valueOf(record.partition()));
            metadata.put("offset", String.valueOf(record.offset()));
            if (receiver.getGroupId() != null) {
                metadata.put("group", receiver.getGroupId());
            }
            record.headers().forEach(h -> metadata.put("header " + h.key(), Payloads.text(h.value())));
            return new MessageDescription("kafka", true, record.topic(), Payloads.text(record.key()),
                    receiver.getListenerId(), Payloads.text(record.value()), metadata);
        }
        if (context instanceof KafkaRecordSenderContext sender) {
            ProducerRecord<?, ?> record = sender.getRecord();
            Map<String, String> metadata = new LinkedHashMap<>();
            if (record.partition() != null) {
                metadata.put("partition", String.valueOf(record.partition()));
            }
            if (sender.getBeanName() != null) {
                metadata.put("template", sender.getBeanName());
            }
            record.headers().forEach(h -> metadata.put("header " + h.key(), Payloads.text(h.value())));
            return new MessageDescription("kafka", false, record.topic(), Payloads.text(record.key()), null,
                    Payloads.text(record.value()), metadata);
        }
        return null;
    }
}
