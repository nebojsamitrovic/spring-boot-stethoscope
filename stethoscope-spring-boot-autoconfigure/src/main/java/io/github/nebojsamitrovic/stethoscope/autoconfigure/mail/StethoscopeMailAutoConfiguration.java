package io.github.nebojsamitrovic.stethoscope.autoconfigure.mail;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.TypePreservingProxy;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Mail watcher: wraps every {@link JavaMailSender} bean in a proxy that keeps its concrete type, so
 * injecting {@code JavaMailSenderImpl} keeps working.
 */
@AutoConfiguration
@ConditionalOnClass(name = {"org.springframework.mail.javamail.JavaMailSender", "jakarta.mail.internet.MimeMessage"})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.mail", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StethoscopeMailAutoConfiguration {

    /** Static: BeanPostProcessors must be created before regular beans. */
    @Bean
    public static MailSenderWrappingPostProcessor stethoscopeMailSenderWrappingPostProcessor(
            ObjectProvider<Recorder> recorder) {
        return new MailSenderWrappingPostProcessor(recorder);
    }

    public static class MailSenderWrappingPostProcessor implements BeanPostProcessor, Ordered {

        private final ObjectProvider<Recorder> recorder;

        public MailSenderWrappingPostProcessor(ObjectProvider<Recorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof JavaMailSender sender && !(bean instanceof RecordingJavaMailSender)
                    && !TypePreservingProxy.isWrapped(bean)) {
                return TypePreservingProxy.wrap(sender, JavaMailSender.class,
                        new RecordingJavaMailSender(sender, recorder::getIfAvailable));
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
