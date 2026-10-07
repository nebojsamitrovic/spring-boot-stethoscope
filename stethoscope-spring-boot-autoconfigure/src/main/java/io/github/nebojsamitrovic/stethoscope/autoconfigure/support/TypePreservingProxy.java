package io.github.nebojsamitrovic.stethoscope.autoconfigure.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;

/**
 * Wraps a bean so that the methods of one interface go to a recording wrapper while the bean keeps
 * its concrete type. Users who inject {@code JavaMailSenderImpl} or {@code CaffeineCacheManager}
 * keep working; everything outside the interface goes straight to the original bean.
 */
public final class TypePreservingProxy {

    private TypePreservingProxy() {
    }

    /** Marker so a bean is never wrapped twice. */
    public interface Wrapped {
    }

    /**
     * @param target    the original bean
     * @param api       interface whose methods should be routed to {@code wrapper}
     * @param wrapper   implementation of {@code api} that records and then delegates to {@code target}
     * @return a class-based proxy of {@code target}, or {@code wrapper} itself if the class cannot be
     *         subclassed (e.g. it is final)
     */
    public static <T> Object wrap(T target, Class<T> api, T wrapper) {
        try {
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addInterface(Wrapped.class);
            factory.addAdvice((MethodInterceptor) invocation -> {
                Method method = invocation.getMethod();
                Method apiMethod = find(api, method);
                if (apiMethod == null) {
                    return invocation.proceed();
                }
                try {
                    return apiMethod.invoke(wrapper, invocation.getArguments());
                } catch (InvocationTargetException ex) {
                    throw ex.getTargetException();
                }
            });
            return factory.getProxy(target.getClass().getClassLoader());
        } catch (RuntimeException | LinkageError ex) {
            return wrapper;
        }
    }

    public static boolean isWrapped(Object bean) {
        return bean instanceof Wrapped;
    }

    private static Method find(Class<?> api, Method method) {
        try {
            return api.getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
