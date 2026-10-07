package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides who may open the dashboard. The default allows the IPs in {@code stethoscope.allowed-ips}
 * (localhost only).
 *
 * <p>Declare your own bean to plug in real authorization, for example:
 *
 * <pre>
 * &#64;Bean
 * StethoscopeGate stethoscopeGate() {
 *     return request -&gt; request.isUserInRole("ADMIN");
 * }
 * </pre>
 */
@FunctionalInterface
public interface StethoscopeGate {

    boolean allows(HttpServletRequest request);
}
