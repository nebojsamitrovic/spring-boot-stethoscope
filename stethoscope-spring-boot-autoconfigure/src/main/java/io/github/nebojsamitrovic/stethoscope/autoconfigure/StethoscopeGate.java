package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Decides who may open the dashboard. The default allows the IPs in {@code stethoscope.allowed-ips}
 * (localhost only) and, when {@code stethoscope.basic-auth.*} is set, also asks for those credentials.
 *
 * <p>The gate runs before Spring Security's filter chain, so the request carries no authenticated
 * user yet: {@code request.isUserInRole(..)} is always {@code false} here. Declare your own bean to
 * check something the request itself carries, for example:
 *
 * <pre>
 * &#64;Bean
 * StethoscopeGate stethoscopeGate() {
 *     return request -&gt; "10.0.0.5".equals(request.getRemoteAddr());
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
