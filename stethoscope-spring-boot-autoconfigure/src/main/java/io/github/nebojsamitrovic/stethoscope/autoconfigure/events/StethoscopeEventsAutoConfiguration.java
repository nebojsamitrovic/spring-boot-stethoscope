package io.github.nebojsamitrovic.stethoscope.autoconfigure.events;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Application event watcher. */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.events", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Recorder.class)
public class StethoscopeEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeEventListener stethoscopeEventListener(Recorder recorder, StethoscopeProperties properties) {
        return new StethoscopeEventListener(recorder, properties.getEvents().getIgnorePackages());
    }
}
