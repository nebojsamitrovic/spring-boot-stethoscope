package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Decides who may open the dashboard. The default allows the IPs in {@code stethoscope.allowed-ips}
 * (localhost only) and, when configured, also asks for {@code stethoscope.basic-auth.*} credentials
 * and/or a user signed in to the application ({@code stethoscope.login.*}).
 *
 * <p>The gate runs after Spring Security's filter chain, so the signed-in user is known:
 * {@code request.isUserInRole(..)}, {@code request.getUserPrincipal()} and
 * {@code SecurityContextHolder} all work. Declare your own bean to replace the default, for example:
 *
 * <pre>
 * &#64;Bean
 * StethoscopeGate stethoscopeGate() {
 *     return request -&gt; request.isUserInRole("ADMIN") || "10.0.0.5".equals(request.getRemoteAddr());
 * }
 * </pre>
 */
@FunctionalInterface
public interface StethoscopeGate {

    boolean allows(HttpServletRequest request);

    /** Answers a request {@link #allows} refused. */
    default void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Stethoscope: access denied");
    }

    /** A gate that lets a request through only when every one of {@code gates} does. */
    static StethoscopeGate allOf(StethoscopeGate... gates) {
        return new StethoscopeGate() {
            @Override
            public boolean allows(HttpServletRequest request) {
                for (StethoscopeGate gate : gates) {
                    if (!gate.allows(request)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
                for (StethoscopeGate gate : gates) {
                    if (!gate.allows(request)) {
                        gate.deny(request, response);
                        return;
                    }
                }
                StethoscopeGate.super.deny(request, response);
            }
        };
    }
}
