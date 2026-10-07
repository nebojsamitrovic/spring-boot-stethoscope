package io.github.nebojsamitrovic.stethoscope.autoconfigure.redis;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.TypePreservingProxy;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis watcher: wraps every {@link RedisConnectionFactory} bean (keeping its concrete type, e.g.
 * {@code LettuceConnectionFactory}) so the connections it hands out record their commands.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StethoscopeRedisAutoConfiguration {

    /** Static: BeanPostProcessors must be created before regular beans. */
    @Bean
    public static RedisConnectionFactoryWrappingPostProcessor stethoscopeRedisConnectionFactoryWrappingPostProcessor(
            ObjectProvider<Recorder> recorder) {
        return new RedisConnectionFactoryWrappingPostProcessor(recorder);
    }

    public static class RedisConnectionFactoryWrappingPostProcessor implements BeanPostProcessor, Ordered {

        private static final Set<String> CONNECTION_METHODS = Set.of("getConnection", "getClusterConnection");

        private final ObjectProvider<Recorder> recorder;

        public RedisConnectionFactoryWrappingPostProcessor(ObjectProvider<Recorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof RedisConnectionFactory) || TypePreservingProxy.isWrapped(bean)) {
                return bean;
            }
            Object proxy = TypePreservingProxy.intercept(bean, invocation -> {
                Object result = invocation.proceed();
                if (CONNECTION_METHODS.contains(invocation.getMethod().getName()) && invocation.getArguments().length == 0) {
                    return RecordingRedisConnections.wrap(result, recorder::getIfAvailable);
                }
                return result;
            });
            return proxy != null ? proxy : bean;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
