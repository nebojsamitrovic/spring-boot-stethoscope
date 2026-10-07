package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.cache.StethoscopeCacheAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.dump.StethoscopeDumpAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.events.StethoscopeEventListener;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.events.StethoscopeEventsAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.http.StethoscopeClientHttpRequestInterceptor;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.http.StethoscopeHttpClientAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc.DataSourceWrappingPostProcessor;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.jobs.StethoscopeJobsAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging.StethoscopeMessagingAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.models.StethoscopeModelsAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.redis.StethoscopeRedisAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.security.StethoscopeSecurityAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.security.StethoscopeSecurityListener;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.logging.StethoscopeLoggingAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.mail.StethoscopeMailAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.scheduling.StethoscopeSchedulingAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.TypePreservingProxy;
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
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StethoscopeAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    StethoscopeAutoConfiguration.class,
                    StethoscopeWebMvcAutoConfiguration.class,
                    StethoscopeJdbcAutoConfiguration.class,
                    StethoscopeLoggingAutoConfiguration.class,
                    StethoscopeHttpClientAutoConfiguration.class,
                    StethoscopeSchedulingAutoConfiguration.class,
                    StethoscopeEventsAutoConfiguration.class,
                    StethoscopeCacheAutoConfiguration.class,
                    StethoscopeMailAutoConfiguration.class,
                    StethoscopeDumpAutoConfiguration.class,
                    StethoscopeJobsAutoConfiguration.class,
                    StethoscopeModelsAutoConfiguration.class,
                    StethoscopeSecurityAutoConfiguration.class,
                    StethoscopeRedisAutoConfiguration.class,
                    StethoscopeMessagingAutoConfiguration.class));

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
            assertThat(context).doesNotHaveBean(StethoscopeLoggingAutoConfiguration.LogbackRegistration.class);
            assertThat(context).doesNotHaveBean(StethoscopeEventListener.class);
            assertThat(context).doesNotHaveBean(StethoscopeCacheAutoConfiguration.CacheManagerWrappingPostProcessor.class);
            assertThat(context).doesNotHaveBean(StethoscopeMailAutoConfiguration.MailSenderWrappingPostProcessor.class);
            assertThat(context).doesNotHaveBean(StethoscopeDumpAutoConfiguration.DumpInstaller.class);
            assertThat(context).doesNotHaveBean(StethoscopeJobsAutoConfiguration.TaskExecutorWrappingPostProcessor.class);
            assertThat(context).doesNotHaveBean(StethoscopeSecurityListener.class);
            assertThat(context).doesNotHaveBean(StethoscopeRedisAutoConfiguration.RedisConnectionFactoryWrappingPostProcessor.class);
            assertThat(context).doesNotHaveBean(StethoscopeMessagingAutoConfiguration.ObservationRegistryPostProcessor.class);
            assertThat(context).doesNotHaveBean(ObservationRegistry.class);
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
            assertThat(context).hasSingleBean(StethoscopeLoggingAutoConfiguration.LogbackRegistration.class);
            assertThat(context).hasSingleBean(StethoscopeClientHttpRequestInterceptor.class);
            assertThat(context).hasSingleBean(StethoscopeSchedulingAutoConfiguration.StethoscopeSchedulingConfigurer.class);
            assertThat(context).hasSingleBean(StethoscopeEventListener.class);
            assertThat(context).hasSingleBean(StethoscopeCacheAutoConfiguration.CacheManagerWrappingPostProcessor.class);
            assertThat(context).hasSingleBean(StethoscopeMailAutoConfiguration.MailSenderWrappingPostProcessor.class);
            assertThat(context).hasSingleBean(StethoscopeDumpAutoConfiguration.DumpInstaller.class);
            assertThat(context).hasSingleBean(StethoscopeJobsAutoConfiguration.TaskExecutorWrappingPostProcessor.class);
            assertThat(context).hasSingleBean(StethoscopeSecurityListener.class);
            assertThat(context).hasSingleBean(StethoscopeRedisAutoConfiguration.RedisConnectionFactoryWrappingPostProcessor.class);
            assertThat(context).hasSingleBean(StethoscopeMessagingAutoConfiguration.ObservationRegistryPostProcessor.class);
        });
    }

    @Test
    void eachWatcherCanBeSwitchedOff() {
        webRunner.withPropertyValues("stethoscope.enabled=true", "stethoscope.logs.enabled=false",
                        "stethoscope.http-client.enabled=false", "stethoscope.scheduled.enabled=false",
                        "stethoscope.events.enabled=false", "stethoscope.cache.enabled=false", "stethoscope.mail.enabled=false",
                        "stethoscope.jobs.enabled=false", "stethoscope.models.enabled=false", "stethoscope.security.enabled=false",
                        "stethoscope.redis.enabled=false", "stethoscope.messages.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(Recorder.class);
                    assertThat(context).doesNotHaveBean(StethoscopeLoggingAutoConfiguration.LogbackRegistration.class);
                    assertThat(context).doesNotHaveBean(StethoscopeClientHttpRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean(StethoscopeSchedulingAutoConfiguration.StethoscopeSchedulingConfigurer.class);
                    assertThat(context).doesNotHaveBean(StethoscopeEventListener.class);
                    assertThat(context).doesNotHaveBean(StethoscopeCacheAutoConfiguration.CacheManagerWrappingPostProcessor.class);
                    assertThat(context).doesNotHaveBean(StethoscopeMailAutoConfiguration.MailSenderWrappingPostProcessor.class);
                    assertThat(context).doesNotHaveBean(StethoscopeJobsAutoConfiguration.TaskExecutorWrappingPostProcessor.class);
                    assertThat(context).doesNotHaveBean(StethoscopeModelsAutoConfiguration.HibernateListenerRegistration.class);
                    assertThat(context).doesNotHaveBean(StethoscopeSecurityListener.class);
                    assertThat(context).doesNotHaveBean(StethoscopeRedisAutoConfiguration.RedisConnectionFactoryWrappingPostProcessor.class);
                    assertThat(context).doesNotHaveBean(StethoscopeMessagingAutoConfiguration.ObservationRegistryPostProcessor.class);
                    assertThat(context).doesNotHaveBean(ObservationRegistry.class);
                });
    }

    @Test
    void taskExecutorsAreWrappedButSchedulersAreNot() {
        webRunner.withPropertyValues("stethoscope.enabled=true")
                .withBean(ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new)
                .withBean(ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    assertThat(TypePreservingProxy.isWrapped(context.getBean(ThreadPoolTaskExecutor.class))).isTrue();
                    assertThat(TypePreservingProxy.isWrapped(context.getBean(ThreadPoolTaskScheduler.class))).isFalse();
                });
    }

    @Test
    void kafkaObservationIsSwitchedOnWithAFallbackRegistry() {
        webRunner.withPropertyValues("stethoscope.enabled=true")
                .withBean(ConcurrentKafkaListenerContainerFactory.class, ConcurrentKafkaListenerContainerFactory::new)
                .withBean(KafkaTemplate.class, () -> new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of())))
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(ConcurrentKafkaListenerContainerFactory.class).getContainerProperties()
                            .isObservationEnabled()).isTrue();
                });
    }

    @Test
    void anExistingObservationRegistryIsKept() {
        ObservationRegistry registry = ObservationRegistry.create();
        webRunner.withPropertyValues("stethoscope.enabled=true")
                .withBean(ObservationRegistry.class, () -> registry)
                .run(context -> assertThat(context.getBean(ObservationRegistry.class)).isSameAs(registry));
    }

    @Test
    void wrappedBeansKeepTheirConcreteType() {
        webRunner.withPropertyValues("stethoscope.enabled=true")
                .withBean(ConcurrentMapCacheManager.class, ConcurrentMapCacheManager::new)
                .withBean(JavaMailSenderImpl.class, JavaMailSenderImpl::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConcurrentMapCacheManager.class);
                    assertThat(context).hasSingleBean(JavaMailSenderImpl.class);
                    assertThat(TypePreservingProxy.isWrapped(context.getBean(CacheManager.class))).isTrue();
                    assertThat(TypePreservingProxy.isWrapped(context.getBean(JavaMailSender.class))).isTrue();
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
