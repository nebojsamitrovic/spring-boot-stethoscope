package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.InMemoryEntryStore;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.RecorderSettings;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.util.Arrays;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Core beans: the entry store, the recorder and the redactor. Everything is active only with
 * {@code stethoscope.enabled=true}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StethoscopeProperties.class)
public class StethoscopeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StethoscopeAutoConfiguration.class);

    private static final Set<String> PRODUCTION_LIKE_PROFILES = Set.of("prod", "production", "live");

    @Bean
    @ConditionalOnMissingBean
    public EntryStore stethoscopeEntryStore(StethoscopeProperties properties) {
        return new InMemoryEntryStore(properties.getMaxEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public Recorder stethoscopeRecorder(EntryStore store, StethoscopeProperties properties) {
        RecorderSettings settings = new RecorderSettings(
                properties.getQueries().getSlowThreshold().toMillis(),
                properties.getQueries().getDuplicateThreshold(),
                properties.getQueries().isRecordParameters(),
                properties.getExceptions().getMaxStackTraceFrames());
        return new Recorder(store, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    public Redactor stethoscopeRedactor(StethoscopeProperties properties) {
        return new Redactor(properties.getRedactHeaders(), properties.getRedactParameters());
    }

    /** Loud warning when Stethoscope is switched on in what looks like production. */
    @Bean
    public ApplicationRunner stethoscopeProductionWarning(Environment environment, StethoscopeProperties properties) {
        return args -> {
            boolean productionLike = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(p -> PRODUCTION_LIKE_PROFILES.contains(p.toLowerCase(java.util.Locale.ROOT)));
            if (productionLike) {
                log.warn("Stethoscope is ENABLED with a production-like profile {}. It records requests, SQL and "
                        + "exceptions in memory and serves them at '{}'. Set stethoscope.enabled=false unless you "
                        + "really mean it.", Arrays.toString(environment.getActiveProfiles()), properties.normalizedPath());
            } else {
                log.info("Stethoscope is recording. Dashboard: {}", properties.normalizedPath());
            }
        };
    }
}
