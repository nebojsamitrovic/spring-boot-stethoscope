package io.github.nebojsamitrovic.stethoscope.autoconfigure.security;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Spring Security watcher. */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.security.authorization.event.AuthorizationEvent",
        "jakarta.servlet.http.HttpServletRequest"})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Recorder.class)
public class StethoscopeSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeSecurityListener stethoscopeSecurityListener(Recorder recorder, StethoscopeProperties properties) {
        return new StethoscopeSecurityListener(recorder, properties.normalizedPath());
    }
}
