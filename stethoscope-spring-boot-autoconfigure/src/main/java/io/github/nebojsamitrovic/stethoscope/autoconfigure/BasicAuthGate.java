package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * Asks for fixed HTTP Basic credentials ({@code stethoscope.basic-auth.*}). Independent of how the
 * application itself authenticates, so it also works for apps whose users carry bearer tokens a
 * browser tab would not send. Only meaningful over HTTPS.
 */
public class BasicAuthGate implements StethoscopeGate {

    private static final String PREFIX = "Basic ";

    private final byte[] expected;

    public BasicAuthGate(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        this.expected = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean allows(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        byte[] given;
        try {
            given = Base64.getDecoder().decode(header.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, given);
    }

    @Override
    public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"Stethoscope\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Stethoscope: credentials required");
    }
}
