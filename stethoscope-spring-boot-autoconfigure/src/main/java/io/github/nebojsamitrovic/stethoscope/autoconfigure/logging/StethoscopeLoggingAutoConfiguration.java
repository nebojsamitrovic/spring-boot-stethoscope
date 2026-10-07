package io.github.nebojsamitrovic.stethoscope.autoconfigure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Log watcher. Active with Logback (Spring Boot's default) unless {@code stethoscope.logs.enabled=false}. */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnBean(Recorder.class)
public class StethoscopeLoggingAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "stethoscope.logs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LogbackRegistration stethoscopeLogbackRegistration(Recorder recorder, StethoscopeProperties properties) {
        StethoscopeProperties.Logs logs = properties.getLogs();
        return new LogbackRegistration(new StethoscopeLogAppender(
                recorder, logs.getLevel(), logs.getIgnoreLoggers(), logs.isRecordExceptions()));
    }

    /** Attaches the appender to the root logger for the lifetime of the application context. */
    public static class LogbackRegistration implements InitializingBean, DisposableBean {

        private final StethoscopeLogAppender appender;

        public LogbackRegistration(StethoscopeLogAppender appender) {
            this.appender = appender;
        }

        public StethoscopeLogAppender appender() {
            return appender;
        }

        @Override
        public void afterPropertiesSet() {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                appender.setName("STETHOSCOPE");
                appender.setContext(context);
                appender.start();
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
            }
        }

        @Override
        public void destroy() {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                root.detachAppender(appender);
            }
            appender.stop();
        }
    }
}
