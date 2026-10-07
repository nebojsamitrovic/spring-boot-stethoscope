package io.github.nebojsamitrovic.stethoscope.autoconfigure.dump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Stethoscope;
import java.util.function.Function;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects {@link Stethoscope#dump} to the recorder. Values are rendered as pretty JSON when Jackson
 * is available, otherwise with {@code toString()}.
 */
@AutoConfiguration(after = {StethoscopeAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnBean(Recorder.class)
public class StethoscopeDumpAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    static class WithJackson {

        @Bean
        DumpInstaller stethoscopeDumpInstaller(Recorder recorder, ObjectProvider<ObjectMapper> objectMapper) {
            ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter()
                    .without(SerializationFeature.FAIL_ON_EMPTY_BEANS);
            return new DumpInstaller(recorder, value -> {
                if (value == null || value instanceof CharSequence || value instanceof Number
                        || value instanceof Boolean || value instanceof Character || value instanceof Throwable) {
                    return String.valueOf(value);
                }
                try {
                    return writer.writeValueAsString(value);
                } catch (Exception ex) {
                    return String.valueOf(value);
                }
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("com.fasterxml.jackson.databind.ObjectMapper")
    static class WithoutJackson {

        @Bean
        DumpInstaller stethoscopeDumpInstaller(Recorder recorder) {
            return new DumpInstaller(recorder, null);
        }
    }

    /** Installs the static API for the lifetime of the application context. */
    public static class DumpInstaller implements InitializingBean, DisposableBean {

        private final Recorder recorder;
        private final Function<Object, String> renderer;

        public DumpInstaller(Recorder recorder, Function<Object, String> renderer) {
            this.recorder = recorder;
            this.renderer = renderer;
        }

        @Override
        public void afterPropertiesSet() {
            Stethoscope.install(recorder, renderer);
        }

        @Override
        public void destroy() {
            Stethoscope.uninstall(recorder);
        }
    }
}
