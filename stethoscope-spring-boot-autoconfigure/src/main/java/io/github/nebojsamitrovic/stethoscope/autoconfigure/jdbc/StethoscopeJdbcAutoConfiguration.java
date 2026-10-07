package io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * SQL watcher. Active when datasource-proxy is on the classpath (the starter brings it) and
 * {@code stethoscope.queries.enabled} is not {@code false}.
 */
@AutoConfiguration
@ConditionalOnClass({DataSource.class, ProxyDataSourceBuilder.class})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
public class StethoscopeJdbcAutoConfiguration {

    /** Static: BeanPostProcessors must be created before regular beans. */
    @Bean
    @ConditionalOnProperty(prefix = "stethoscope.queries", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static DataSourceWrappingPostProcessor stethoscopeDataSourceWrappingPostProcessor(
            ObjectProvider<Recorder> recorder) {
        return new DataSourceWrappingPostProcessor(recorder);
    }
}
