package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Lets in users signed in to the application ({@code stethoscope.login.*}), optionally only those
 * with one of a few roles. Uses the servlet API only ({@code getUserPrincipal}, {@code isUserInRole}),
 * which Spring Security backs with its {@code SecurityContext}, so it needs no Spring Security
 * classes. Works because the gate runs after Spring Security's filter chain.
 */
public class LoginGate implements StethoscopeGate {

    private final List<String> roles;

    public LoginGate(List<String> roles) {
        this.roles = roles == null ? List.of()
                : roles.stream().filter(r -> r != null && !r.isBlank()).map(String::trim).toList();
    }

    @Override
    public boolean allows(HttpServletRequest request) {
        if (request.getUserPrincipal() == null) {
            return false;
        }
        if (roles.isEmpty()) {
            return true;
        }
        for (String role : roles) {
            if (request.isUserInRole(role)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getUserPrincipal() == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Stethoscope: sign in first");
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Stethoscope: access denied");
        }
    }
}
