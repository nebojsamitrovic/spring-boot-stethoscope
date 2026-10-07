package io.github.nebojsamitrovic.stethoscope.autoconfigure.web;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.AllowedIpsGate;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeGate;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.StethoscopeUiController;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Servlet / Spring MVC integration: request watcher filter, exception capture and the dashboard UI.
 *
 * <p>The filter is registered as a plain {@code Filter} bean; Spring Boot picks it up and applies
 * it to all requests, ordered by {@link RequestWatcherFilter#ORDER}.
 */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnBean(Recorder.class)
public class StethoscopeWebMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeGate stethoscopeGate(StethoscopeProperties properties) {
        return new AllowedIpsGate(properties.getAllowedIps());
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestWatcherFilter stethoscopeRequestWatcherFilter(
            Recorder recorder, Redactor redactor, StethoscopeProperties properties, StethoscopeGate gate) {
        return new RequestWatcherFilter(recorder, redactor, properties, gate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExceptionCapturingResolver stethoscopeExceptionCapturingResolver() {
        return new ExceptionCapturingResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeUiController stethoscopeUiController(Recorder recorder, StethoscopeProperties properties) {
        return new StethoscopeUiController(recorder, properties);
    }
}
