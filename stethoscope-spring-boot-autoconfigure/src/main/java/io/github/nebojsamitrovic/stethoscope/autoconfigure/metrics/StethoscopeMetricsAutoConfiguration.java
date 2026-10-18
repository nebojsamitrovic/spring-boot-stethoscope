package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics behind the dashboard's overview page: aggregates derived from entries, kept in the
 * application's database. Needs a single {@link DataSource}; {@code stethoscope.metrics.enabled=false}
 * switches it off. Without it the dashboard opens on the request list as before.
 */
@AutoConfiguration(
        after = StethoscopeAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", // Boot 3
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration" // Boot 4
        })
@ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnBean(Recorder.class)
@ConditionalOnSingleCandidate(DataSource.class)
public class StethoscopeMetricsAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "stethoscope.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Collecting {

        @Bean
        @ConditionalOnMissingBean
        MetricsAggregator stethoscopeMetricsAggregator(StethoscopeProperties properties) {
            return new MetricsAggregator(properties.getMetrics().getMaxPendingKeys());
        }

        @Bean
        @ConditionalOnMissingBean
        MetricsStore stethoscopeMetricsStore(DataSource dataSource, StethoscopeProperties properties) {
            return new JdbcMetricsStore(dataSource, properties.getMetrics().isInitializeSchema());
        }

        @Bean
        @ConditionalOnMissingBean
        MetricsScheduler stethoscopeMetricsScheduler(
                MetricsAggregator aggregator, MetricsStore store, StethoscopeProperties properties) {
            StethoscopeProperties.Metrics settings = properties.getMetrics();
            Clock clock = Clock.systemUTC();
            SystemSampler sampler = settings.isSystem()
                    ? new SystemSampler(aggregator, settings.getInstanceName(), clock)
                    : null;
            return new MetricsScheduler(aggregator, store, sampler, settings.getFlushInterval(),
                    settings.getRetention(), clock);
        }
    }
}
