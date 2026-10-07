package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc.DataSourceWrappingPostProcessor;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc.StethoscopeJdbcAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.StethoscopeUiController;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.web.ExceptionCapturingResolver;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.web.RequestWatcherFilter;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.web.StethoscopeWebMvcAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.InMemoryEntryStore;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StethoscopeAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    StethoscopeAutoConfiguration.class,
                    StethoscopeWebMvcAutoConfiguration.class,
                    StethoscopeJdbcAutoConfiguration.class));

    private final ApplicationContextRunner jdbcRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    StethoscopeAutoConfiguration.class,
                    StethoscopeJdbcAutoConfiguration.class));

    @Test
    void registersNothingWhenEnabledIsMissing() {
        webRunner.run(context -> {
            assertThat(context).doesNotHaveBean(Recorder.class);
            assertThat(context).doesNotHaveBean(EntryStore.class);
            assertThat(context).doesNotHaveBean(RequestWatcherFilter.class);
            assertThat(context).doesNotHaveBean(StethoscopeUiController.class);
            assertThat(context).doesNotHaveBean(DataSourceWrappingPostProcessor.class);
        });
    }

    @Test
    void registersNothingWhenDisabled() {
        webRunner.withPropertyValues("stethoscope.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(Recorder.class);
            assertThat(context).doesNotHaveBean(RequestWatcherFilter.class);
            assertThat(context).doesNotHaveBean(DataSourceWrappingPostProcessor.class);
        });
    }

    @Test
    void registersAllBeansWhenEnabled() {
        webRunner.withPropertyValues("stethoscope.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(EntryStore.class);
            assertThat(context).hasSingleBean(Recorder.class);
            assertThat(context).hasSingleBean(Redactor.class);
            assertThat(context).hasSingleBean(StethoscopeGate.class);
            assertThat(context).hasSingleBean(RequestWatcherFilter.class);
            assertThat(context).hasSingleBean(ExceptionCapturingResolver.class);
            assertThat(context).hasSingleBean(StethoscopeUiController.class);
            assertThat(context).hasSingleBean(DataSourceWrappingPostProcessor.class);
        });
    }

    @Test
    void webBeansAreSkippedOutsideAWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        StethoscopeAutoConfiguration.class, StethoscopeWebMvcAutoConfiguration.class))
                .withPropertyValues("stethoscope.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(Recorder.class);
                    assertThat(context).doesNotHaveBean(RequestWatcherFilter.class);
                    assertThat(context).doesNotHaveBean(StethoscopeUiController.class);
                });
    }

    @Test
    void userBeansWinOverDefaults() {
        webRunner.withPropertyValues("stethoscope.enabled=true")
                .withUserConfiguration(UserBeans.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EntryStore.class);
                    assertThat(context.getBean(EntryStore.class)).isSameAs(UserBeans.STORE);
                    assertThat(context.getBean(Recorder.class).store()).isSameAs(UserBeans.STORE);
                    assertThat(context).hasSingleBean(StethoscopeGate.class);
                    assertThat(context.getBean(StethoscopeGate.class)).isSameAs(UserBeans.GATE);
                });
    }

    @Test
    void wrapsTheDataSource() {
        jdbcRunner.withPropertyValues("stethoscope.enabled=true", "spring.datasource.url=jdbc:h2:mem:wrap")
                .run(context -> assertThat(context.getBean(DataSource.class)).isInstanceOf(ProxyDataSource.class));
    }

    @Test
    void leavesTheDataSourceAloneWhenQueriesAreDisabled() {
        jdbcRunner.withPropertyValues("stethoscope.enabled=true", "stethoscope.queries.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:nowrap")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DataSourceWrappingPostProcessor.class);
                    assertThat(context.getBean(DataSource.class)).isNotInstanceOf(ProxyDataSource.class);
                });
    }

    @Test
    void leavesTheDataSourceAloneWhenDisabled() {
        jdbcRunner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:off")
                .run(context -> assertThat(context.getBean(DataSource.class)).isNotInstanceOf(ProxyDataSource.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBeans {

        static final EntryStore STORE = new InMemoryEntryStore(10);
        static final StethoscopeGate GATE = request -> false;

        @Bean
        EntryStore myStore() {
            return STORE;
        }

        @Bean
        StethoscopeGate myGate() {
            return GATE;
        }
    }
}
