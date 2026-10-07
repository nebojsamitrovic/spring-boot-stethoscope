package io.github.nebojsamitrovic.stethoscope.autoconfigure.security;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.authorization.event.AuthorizationEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Records Spring Security decisions, the counterpart of Telescope's "Gates": logins, failed logins,
 * logouts and authorization results. Spring Security publishes authorization denials by default;
 * grants only when an {@code AuthorizationEventPublisher} is configured to publish them.
 */
public class StethoscopeSecurityListener implements ApplicationListener<ApplicationEvent> {

    static final String AUTHENTICATION = "authentication";
    static final String AUTHORIZATION = "authorization";

    private final Recorder recorder;
    private final String dashboardPath;

    public StethoscopeSecurityListener(Recorder recorder, String dashboardPath) {
        this.recorder = recorder;
        this.dashboardPath = dashboardPath;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            if (!recorder.isRecording()) {
                return;
            }
            if (event instanceof AuthorizationEvent authorization) {
                onAuthorization(authorization);
            } else if (event instanceof AuthenticationSuccessEvent success) {
                record(AUTHENTICATION, "authenticated", success.getAuthentication(), null, null, null);
            } else if (event instanceof AbstractAuthenticationFailureEvent failure) {
                record(AUTHENTICATION, "failed", failure.getAuthentication(), null, null,
                        failure.getException().getClass().getSimpleName() + ": " + failure.getException().getMessage());
            } else if (event instanceof LogoutSuccessEvent logout) {
                record(AUTHENTICATION, "logout", logout.getAuthentication(), null, null, null);
            }
        } catch (RuntimeException ignored) {
            // never break security because of the debugger
        }
    }

    private void onAuthorization(AuthorizationEvent event) {
        String resource = describe(event.getObject());
        if (resource != null && isDashboard(resource)) {
            return;
        }
        AuthorizationResult result = event.getAuthorizationResult();
        boolean granted = !(event instanceof AuthorizationDeniedEvent<?>) && result != null && result.isGranted();
        Authentication authentication;
        try {
            authentication = event.getAuthentication().get();
        } catch (RuntimeException ex) {
            authentication = null;
        }
        record(AUTHORIZATION, granted ? "granted" : "denied", authentication, resource,
                result == null ? null : result.toString(), null);
    }

    private void record(String kind, String result, Authentication authentication, String resource, String details,
            String error) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(Entry.Content.KIND, kind);
        content.put(Entry.Content.RESULT, result);
        content.put(Entry.Content.PRINCIPAL, authentication == null || authentication.getName() == null
                ? "anonymous" : authentication.getName());
        if (authentication != null && authentication.getAuthorities() != null) {
            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).filter(a -> a != null).sorted().toList();
            if (!authorities.isEmpty()) {
                content.put(Entry.Content.AUTHORITIES, authorities);
            }
        }
        if (resource != null) {
            content.put(Entry.Content.RESOURCE, resource);
        }
        if (details != null) {
            content.put(Entry.Content.DETAILS, details);
        }
        if (error != null) {
            content.put(Entry.Content.ERROR, error);
        }
        Set<String> tags = new HashSet<>(Set.of(kind, result));
        if ("denied".equals(result) || "failed".equals(result)) {
            tags.add(Entry.Tags.FAILED);
        }
        recorder.record(EntryType.SECURITY, content, tags);
    }

    /** {@code GET /orders}, {@code OrderService#cancel}, or the object's class name. */
    static String describe(Object object) {
        if (object == null) {
            return null;
        }
        if (object instanceof HttpServletRequest request) {
            return request.getMethod() + " " + request.getRequestURI();
        }
        if (object instanceof MethodInvocation invocation) {
            Method method = invocation.getMethod();
            Object target = invocation.getThis();
            String type = target != null ? target.getClass().getName() : method.getDeclaringClass().getName();
            int cglib = type.indexOf("$$");
            return (cglib > 0 ? type.substring(0, cglib) : type) + "#" + method.getName();
        }
        try {
            // RequestAuthorizationContext (spring-security-web) without a compile-time dependency
            Method getRequest = object.getClass().getMethod("getRequest");
            if (getRequest.invoke(object) instanceof HttpServletRequest request) {
                return request.getMethod() + " " + request.getRequestURI();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not a request wrapper
        }
        return object.getClass().getName();
    }

    private boolean isDashboard(String resource) {
        int space = resource.indexOf(' ');
        String path = space < 0 ? resource : resource.substring(space + 1);
        return path.equals(dashboardPath) || path.startsWith(dashboardPath + "/") || path.contains(dashboardPath + "/");
    }
}
