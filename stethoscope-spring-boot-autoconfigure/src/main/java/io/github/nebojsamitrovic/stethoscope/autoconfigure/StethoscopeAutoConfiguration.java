package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import io.github.nebojsamitrovic.stethoscope.core.DiscardingEntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryListener;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.InMemoryEntryStore;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.RecorderSettings;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
        return properties.isStoreEntries()
                ? new InMemoryEntryStore(properties.getMaxEntries())
                : new DiscardingEntryStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public Recorder stethoscopeRecorder(
            EntryStore store, StethoscopeProperties properties, ObjectProvider<EntryListener> listeners) {
        RecorderSettings settings = new RecorderSettings(
                properties.getQueries().getSlowThreshold().toMillis(),
                properties.getQueries().getDuplicateThreshold(),
                properties.isStoreEntries() && properties.getQueries().isRecordParameters(),
                properties.getExceptions().getMaxStackTraceFrames());
        return new Recorder(store, settings, Clock.systemUTC(), listeners.orderedStream().toList());
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
            if (productionLike && properties.isStoreEntries()) {
                log.warn("Stethoscope is ENABLED with a production-like profile {}. It records requests, SQL and "
                        + "exceptions in memory and serves them at '{}'. Set stethoscope.enabled=false unless you "
                        + "really mean it.", Arrays.toString(environment.getActiveProfiles()), properties.normalizedPath());
            } else if (properties.isStoreEntries()) {
                log.info("Stethoscope is recording. Dashboard: {}", properties.normalizedPath());
            } else {
                log.info("Stethoscope is recording without keeping entries (store-entries=false). Dashboard: {}",
                        properties.normalizedPath());
            }
        };
    }
}
