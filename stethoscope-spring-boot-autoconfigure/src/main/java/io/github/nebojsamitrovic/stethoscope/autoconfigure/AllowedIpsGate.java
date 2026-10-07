package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default {@link StethoscopeGate}: allows clients whose remote address is in a fixed list.
 *
 * <p>Uses {@link HttpServletRequest#getRemoteAddr()} on purpose and ignores {@code X-Forwarded-For},
 * which any client can forge. If the app runs behind a proxy, configure Spring Boot's
 * {@code server.forward-headers-strategy} or provide your own gate.
 */
public class AllowedIpsGate implements StethoscopeGate {

    private final Set<String> allowed;

    public AllowedIpsGate(List<String> allowedIps) {
        this.allowed = allowedIps == null ? Set.of()
                : allowedIps.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean allows(HttpServletRequest request) {
        return allowed.isEmpty() || allowed.contains(request.getRemoteAddr());
    }
}
