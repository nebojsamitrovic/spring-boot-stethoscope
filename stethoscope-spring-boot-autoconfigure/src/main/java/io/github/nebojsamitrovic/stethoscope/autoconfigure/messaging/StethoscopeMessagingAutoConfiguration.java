package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Message watcher for Kafka and RabbitMQ, built on the observation support in spring-kafka and
 * spring-rabbit. It switches observation on for listener container factories and templates and adds
 * its handler to the application's {@link ObservationRegistry}. Without Spring Boot Actuator there is
 * no registry, so one is provided.
 */
@AutoConfiguration(
        after = StethoscopeAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration")
@ConditionalOnClass(name = "io.micrometer.observation.ObservationRegistry")
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.messages", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StethoscopeMessagingAutoConfiguration {

    /** Adds the handler to every ObservationRegistry bean, Spring Boot's included. */
    @Bean
    public static ObservationRegistryPostProcessor stethoscopeObservationRegistryPostProcessor(
            ObjectProvider<Recorder> recorder, ObjectProvider<Redactor> redactor) {
        return new ObservationRegistryPostProcessor(
                new MessagingObservationHandler(recorder::getIfAvailable, redactor::getIfAvailable));
    }

    public static class ObservationRegistryPostProcessor implements BeanPostProcessor {

        private final MessagingObservationHandler handler;

        public ObservationRegistryPostProcessor(MessagingObservationHandler handler) {
            this.handler = handler;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ObservationRegistry registry && !registry.isNoop()) {
                registry.observationConfig().observationHandler(handler);
            }
            return bean;
        }
    }

    /**
     * Spring Kafka and Spring AMQP only observe when a registry bean exists. One bean at most: the
     * second method is skipped by its {@code @ConditionalOnMissingBean} when both libraries are present.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(ObservationRegistry.class)
    static class FallbackRegistry {

        @Bean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        @ConditionalOnMissingBean
        ObservationRegistry stethoscopeKafkaObservationRegistry() {
            return ObservationRegistry.create();
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
        @ConditionalOnMissingBean
        ObservationRegistry stethoscopeRabbitObservationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    static class Kafka {

        @Bean
        static BeanPostProcessor stethoscopeKafkaObservationEnabler() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                        factory.getContainerProperties().setObservationEnabled(true);
                    } else if (bean instanceof KafkaTemplate<?, ?> template) {
                        template.setObservationEnabled(true);
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
    static class Rabbit {

        @Bean
        static BeanPostProcessor stethoscopeRabbitObservationEnabler() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) {
                        factory.setObservationEnabled(true);
                    } else if (bean instanceof RabbitTemplate template) {
                        template.setObservationEnabled(true);
                    }
                    return bean;
                }
            };
        }
    }
}
