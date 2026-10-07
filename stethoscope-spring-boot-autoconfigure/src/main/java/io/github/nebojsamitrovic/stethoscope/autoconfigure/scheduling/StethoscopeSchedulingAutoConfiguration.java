package io.github.nebojsamitrovic.stethoscope.autoconfigure.scheduling;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Scheduled task watcher, based on the observations Spring Framework 6.1+ emits for every
 * {@code @Scheduled} run. Only takes effect when the application uses {@code @EnableScheduling}.
 */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.scheduling.support.ScheduledTaskObservationContext",
        "io.micrometer.observation.ObservationRegistry"})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.scheduled", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Recorder.class)
public class StethoscopeSchedulingAutoConfiguration {

    @Bean
    public StethoscopeSchedulingConfigurer stethoscopeSchedulingConfigurer(Recorder recorder) {
        return new StethoscopeSchedulingConfigurer(new ScheduledTaskObservationHandler(recorder));
    }

    /**
     * Adds the handler to the registrar's {@link ObservationRegistry}, or gives the registrar one if
     * the application has none (no actuator). Runs after every other configurer so a registry set by
     * Spring Boot's actuator is kept.
     */
    public static class StethoscopeSchedulingConfigurer implements SchedulingConfigurer, Ordered {

        private final ScheduledTaskObservationHandler handler;

        public StethoscopeSchedulingConfigurer(ScheduledTaskObservationHandler handler) {
            this.handler = handler;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            ObservationRegistry registry = registrar.getObservationRegistry();
            if (registry == null || registry.isNoop()) {
                registry = ObservationRegistry.create();
                registrar.setObservationRegistry(registry);
            }
            registry.observationConfig().observationHandler(handler);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
