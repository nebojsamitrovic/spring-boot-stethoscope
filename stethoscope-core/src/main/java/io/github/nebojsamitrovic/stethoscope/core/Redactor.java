package io.github.nebojsamitrovic.stethoscope.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Masks secrets before they are stored: sensitive headers, query parameters and JSON/form fields.
 *
 * <p>Matching is case-insensitive and by exact name, e.g. {@code "password"} masks
 * {@code password} and {@code Password}, but not {@code passwordHint}.
 */
public final class Redactor {

    public static final String MASK = "********";

    public static final Set<String> DEFAULT_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token");

    public static final Set<String> DEFAULT_PARAMETERS = Set.of(
            "password", "passwd", "secret", "token", "access_token", "refresh_token", "client_secret",
            "api_key", "apikey", "card_number", "cvv");

    private final Set<String> headers;
    private final Set<String> parameters;
    private final Pattern jsonField;
    private final Pattern formField;

    public Redactor(Collection<String> headers, Collection<String> parameters) {
        this.headers = lower(headers);
        this.parameters = lower(parameters);
        String names = this.parameters.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        if (names.isEmpty()) {
            this.jsonField = null;
            this.formField = null;
        } else {
            // "password" : "value"   or   "password": 123
            this.jsonField = Pattern.compile(
                    "(?i)(\"(?:" + names + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)");
            // password=value&...
            this.formField = Pattern.compile("(?i)(^|[?&])((?:" + names + ")=)([^&]*)");
        }
    }

    public static Redactor defaults() {
        return new Redactor(DEFAULT_HEADERS, DEFAULT_PARAMETERS);
    }

    public boolean isSensitiveHeader(String name) {
        return name != null && headers.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Copy of the headers with sensitive values masked. Keeps insertion order. */
    public Map<String, String> headers(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((name, value) -> result.put(name, isSensitiveHeader(name) ? MASK : value));
        return result;
    }

    /** Masks sensitive parameters in a query string or form body ({@code a=1&password=x}). */
    public String queryString(String query) {
        if (query == null || formField == null) {
            return query;
        }
        Matcher matcher = formField.matcher(query);
        return matcher.replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
    }

    /** Masks sensitive fields in a body. Handles JSON and form-encoded bodies; other content is returned as-is. */
    public String body(String body) {
        if (body == null || jsonField == null) {
            return body;
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Matcher matcher = jsonField.matcher(body);
            return matcher.replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\"" + MASK + "\""));
        }
        if (body.contains("=") && !body.contains("\n")) {
            return queryString(body);
        }
        return body;
    }

    private static Set<String> lower(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
