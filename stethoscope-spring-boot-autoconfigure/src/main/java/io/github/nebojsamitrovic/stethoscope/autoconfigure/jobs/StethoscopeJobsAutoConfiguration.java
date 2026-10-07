package io.github.nebojsamitrovic.stethoscope.autoconfigure.jobs;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.TypePreservingProxy;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

/**
 * Job watcher: wraps every {@link TaskExecutor} bean (Spring Boot's {@code applicationTaskExecutor}
 * included, which runs {@code @Async} methods) in a proxy that keeps its concrete type and records
 * each submitted task. Task schedulers are left alone; {@code @Scheduled} runs have their own watcher.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StethoscopeJobsAutoConfiguration {

    /** Static: BeanPostProcessors must be created before regular beans. */
    @Bean
    public static TaskExecutorWrappingPostProcessor stethoscopeTaskExecutorWrappingPostProcessor(
            ObjectProvider<Recorder> recorder) {
        return new TaskExecutorWrappingPostProcessor(recorder);
    }

    public static class TaskExecutorWrappingPostProcessor implements BeanPostProcessor, Ordered {

        private static final Set<String> SUBMIT_METHODS = Set.of("execute", "submit", "submitCompletable", "submitListenable");

        private final ObjectProvider<Recorder> recorder;

        public TaskExecutorWrappingPostProcessor(ObjectProvider<Recorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof TaskExecutor) || bean instanceof TaskScheduler || TypePreservingProxy.isWrapped(bean)) {
                return bean;
            }
            Object proxy = TypePreservingProxy.intercept(bean, invocation -> {
                if (SUBMIT_METHODS.contains(invocation.getMethod().getName())) {
                    Object[] arguments = invocation.getArguments();
                    for (int i = 0; i < arguments.length; i++) {
                        arguments[i] = RecordingTasks.wrap(arguments[i], beanName, recorder::getIfAvailable);
                    }
                }
                return invocation.proceed();
            });
            return proxy != null ? proxy : bean;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
