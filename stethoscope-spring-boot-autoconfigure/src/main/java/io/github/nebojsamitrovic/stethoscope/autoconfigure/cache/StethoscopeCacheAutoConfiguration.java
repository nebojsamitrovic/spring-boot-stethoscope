package io.github.nebojsamitrovic.stethoscope.autoconfigure.cache;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.TypePreservingProxy;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Cache watcher: wraps every {@link CacheManager} bean in a proxy that keeps its concrete type, so
 * injecting e.g. {@code CaffeineCacheManager} keeps working.
 */
@AutoConfiguration
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StethoscopeCacheAutoConfiguration {

    /** Static: BeanPostProcessors must be created before regular beans. */
    @Bean
    public static CacheManagerWrappingPostProcessor stethoscopeCacheManagerWrappingPostProcessor(
            ObjectProvider<Recorder> recorder) {
        return new CacheManagerWrappingPostProcessor(recorder);
    }

    public static class CacheManagerWrappingPostProcessor implements BeanPostProcessor, Ordered {

        private final ObjectProvider<Recorder> recorder;

        public CacheManagerWrappingPostProcessor(ObjectProvider<Recorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof CacheManager cacheManager && !(bean instanceof RecordingCacheManager)
                    && !TypePreservingProxy.isWrapped(bean)) {
                return TypePreservingProxy.wrap(cacheManager, CacheManager.class,
                        new RecordingCacheManager(cacheManager, recorder::getIfAvailable));
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
