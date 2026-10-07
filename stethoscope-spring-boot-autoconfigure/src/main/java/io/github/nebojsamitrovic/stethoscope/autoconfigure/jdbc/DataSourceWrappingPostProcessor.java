package io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Wraps every {@link DataSource} bean in a datasource-proxy {@link ProxyDataSource} that reports
 * executed statements to Stethoscope.
 *
 * <p>Note: after wrapping, the bean is a {@code ProxyDataSource}. Code that injects the concrete
 * pool type (e.g. {@code HikariDataSource}) should inject {@code DataSource} and use
 * {@code unwrap(HikariDataSource.class)} instead, or disable the watcher with
 * {@code stethoscope.queries.enabled=false}.
 */
public class DataSourceWrappingPostProcessor implements BeanPostProcessor, Ordered {

    private final ObjectProvider<Recorder> recorder;

    public DataSourceWrappingPostProcessor(ObjectProvider<Recorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
            return ProxyDataSourceBuilder.create(beanName, dataSource)
                    .listener(new StethoscopeQueryListener(recorder::getIfAvailable))
                    .build();
        }
        return bean;
    }

    @Override
    public int getOrder() {
        // wrap last, after other post-processors (e.g. metrics) have done their work on the raw pool
        return Ordered.LOWEST_PRECEDENCE;
    }
}
