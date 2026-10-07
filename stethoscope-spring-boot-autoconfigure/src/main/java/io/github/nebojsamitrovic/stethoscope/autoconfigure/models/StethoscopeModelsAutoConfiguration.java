package io.github.nebojsamitrovic.stethoscope.autoconfigure.models;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Model watcher for JPA with Hibernate: registers {@link HibernateModelListener} on every EntityManagerFactory. */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.event.service.spi.EventListenerRegistry"})
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.models", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Recorder.class)
public class StethoscopeModelsAutoConfiguration {

    @Bean
    public HibernateListenerRegistration stethoscopeHibernateListenerRegistration(
            ObjectProvider<EntityManagerFactory> entityManagerFactories, ObjectProvider<Recorder> recorder, Redactor redactor) {
        return new HibernateListenerRegistration(entityManagerFactories,
                new HibernateModelListener(recorder::getIfAvailable, redactor));
    }

    /** Registers the listener once all singletons, including the EntityManagerFactory beans, exist. */
    public static class HibernateListenerRegistration implements SmartInitializingSingleton {

        private final ObjectProvider<EntityManagerFactory> entityManagerFactories;
        private final HibernateModelListener listener;

        public HibernateListenerRegistration(ObjectProvider<EntityManagerFactory> entityManagerFactories,
                HibernateModelListener listener) {
            this.entityManagerFactories = entityManagerFactories;
            this.listener = listener;
        }

        @Override
        public void afterSingletonsInstantiated() {
            entityManagerFactories.orderedStream().forEach(this::register);
        }

        private void register(EntityManagerFactory entityManagerFactory) {
            try {
                SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
                EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
                if (registry != null) {
                    registry.appendListeners(EventType.POST_INSERT, listener);
                    registry.appendListeners(EventType.POST_UPDATE, listener);
                    registry.appendListeners(EventType.POST_DELETE, listener);
                }
            } catch (RuntimeException ignored) {
                // not Hibernate, or not ready: the model watcher stays off for this factory
            }
        }
    }
}
