package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StethoscopeMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    StethoscopeAutoConfiguration.class,
                    StethoscopeMetricsAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:metrics-autoconfig;DB_CLOSE_DELAY=-1");

    @Test
    void collectsMetricsWhenThereIsADataSource() {
        runner.withPropertyValues("stethoscope.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MetricsAggregator.class);
            assertThat(context).hasSingleBean(MetricsScheduler.class);
            assertThat(context.getBean(MetricsStore.class)).isInstanceOf(JdbcMetricsStore.class);
        });
    }

    @Test
    void canBeSwitchedOffOnItsOwn() {
        runner.withPropertyValues("stethoscope.enabled=true", "stethoscope.metrics.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(Recorder.class);
            assertThat(context).doesNotHaveBean(MetricsAggregator.class);
            assertThat(context).doesNotHaveBean(MetricsStore.class);
            assertThat(context).doesNotHaveBean(MetricsScheduler.class);
        });
    }

    @Test
    void needsADataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        StethoscopeAutoConfiguration.class, StethoscopeMetricsAutoConfiguration.class))
                .withPropertyValues("stethoscope.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(MetricsStore.class));
    }

    @Test
    void registersNothingWhenStethoscopeIsOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MetricsAggregator.class));
    }
}
