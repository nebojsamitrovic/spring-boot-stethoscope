package io.github.nebojsamitrovic.stethoscope.autoconfigure.web;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeGate;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the dashboard path with the {@link StethoscopeGate}. Runs late, after Spring Security's
 * filter chain (order -100 by default), so a gate can see the signed-in user: the request is
 * already wrapped, {@code request.isUserInRole(..)} works and {@code SecurityContextHolder} is set.
 * Guarding in a filter rather than in the controller keeps the gate in front of every route to it.
 */
public class DashboardGateFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    private final StethoscopeProperties properties;
    private final StethoscopeGate gate;

    public DashboardGateFilter(StethoscopeProperties properties, StethoscopeGate gate) {
        this.properties = properties;
        this.gate = gate;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestWatcherFilter.pathWithinApplication(request);
        String base = properties.normalizedPath();
        return !(path.equals(base) || path.startsWith(base + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!gate.allows(request)) {
            gate.deny(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
