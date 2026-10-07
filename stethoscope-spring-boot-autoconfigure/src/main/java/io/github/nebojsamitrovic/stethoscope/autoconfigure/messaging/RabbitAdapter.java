package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.Bodies;
import io.micrometer.observation.Observation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;

/** Only loaded when spring-rabbit is on the classpath. */
final class RabbitAdapter implements MessageDescription.Adapter {

    @Override
    public MessageDescription describe(Observation.Context context) {
        if (context instanceof RabbitMessageReceiverContext receiver) {
            Message message = receiver.getCarrier();
            MessageProperties properties = message.getMessageProperties();
            Map<String, String> metadata = metadata(properties);
            if (properties.getReceivedExchange() != null && !properties.getReceivedExchange().isEmpty()) {
                metadata.put("exchange", properties.getReceivedExchange());
            }
            if (properties.getReceivedRoutingKey() != null) {
                metadata.put("routing key", properties.getReceivedRoutingKey());
            }
            String queue = properties.getConsumerQueue() != null ? properties.getConsumerQueue() : receiver.getSource();
            return new MessageDescription("rabbitmq", true, queue, properties.getMessageId(), receiver.getListenerId(),
                    payload(message), metadata);
        }
        if (context instanceof RabbitMessageSenderContext sender) {
            Message message = sender.getCarrier();
            Map<String, String> metadata = metadata(message.getMessageProperties());
            if (sender.getBeanName() != null) {
                metadata.put("template", sender.getBeanName());
            }
            String exchange = sender.getExchange() == null || sender.getExchange().isEmpty() ? "(default)" : sender.getExchange();
            return new MessageDescription("rabbitmq", false, exchange + " / " + sender.getRoutingKey(),
                    message.getMessageProperties().getMessageId(), null, payload(message), metadata);
        }
        return null;
    }

    private static Map<String, String> metadata(MessageProperties properties) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (properties.getContentType() != null) {
            metadata.put("content type", properties.getContentType());
        }
        properties.getHeaders().forEach((name, value) -> metadata.put("header " + name, Payloads.text(value)));
        return metadata;
    }

    private static String payload(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return null;
        }
        String contentType = message.getMessageProperties().getContentType();
        if (contentType != null && !Bodies.isText(contentType)) {
            return "<" + body.length + " bytes, " + contentType + ">";
        }
        return Payloads.text(body);
    }
}
