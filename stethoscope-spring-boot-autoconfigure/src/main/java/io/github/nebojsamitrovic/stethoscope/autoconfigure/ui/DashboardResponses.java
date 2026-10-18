package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP plumbing of the dashboard: view context, HTML and redirect responses, bundled assets. */
class DashboardResponses {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final Map<String, MediaType> ASSETS = Map.of(
            "stethoscope.css", new MediaType("text", "css", StandardCharsets.UTF_8),
            "htmx.min.js", new MediaType("text", "javascript", StandardCharsets.UTF_8));

    private final Recorder recorder;
    private final EntryStore store;
    private final StethoscopeProperties properties;
    private final boolean overview;
    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();

    /** @param overview whether metrics are collected, so the overview page exists */
    DashboardResponses(Recorder recorder, StethoscopeProperties properties, boolean overview) {
        this.recorder = recorder;
        this.store = recorder.store();
        this.properties = properties;
        this.overview = overview;
    }

    /** The overview when metrics are collected, otherwise the request list. */
    ResponseEntity<Void> redirectToStart(HttpServletRequest request) {
        String target = overview ? "/overview" : "/requests";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base(request) + target)).build();
    }

    ViewContext context(HttpServletRequest request) {
        Map<EntryType, Long> counts = new EnumMap<>(EntryType.class);
        for (EntryType type : EntryType.values()) {
            counts.put(type, store.count(type));
        }
        String[] csrf = csrf(request);
        return new ViewContext(base(request), csrf[0], csrf[1], counts, recorder.isRecording(), Instant.now(),
                ZoneId.systemDefault(), overview);
    }

    ResponseEntity<String> html(String body) {
        return ResponseEntity.ok()
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    ResponseEntity<String> notFound(HttpServletRequest request) {
        return notFound(context(request));
    }

    ResponseEntity<String> notFound(ViewContext ctx) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML).body(Views.notFound(ctx));
    }

    /** Tells htmx to reload the page; plain HTML forms are not used for these actions. */
    ResponseEntity<Void> refresh() {
        return ResponseEntity.noContent().header("HX-Refresh", "true").build();
    }

    ResponseEntity<byte[]> asset(String name) {
        MediaType type = ASSETS.get(name);
        if (type == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = assetCache.computeIfAbsent(name, DashboardResponses::readAsset);
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(bytes);
    }

    private String base(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return contextPath + properties.normalizedPath();
    }

    /**
     * Reads Spring Security's CSRF token, if present, without a compile-time dependency on Spring
     * Security. Returns {@code [headerName, token]} or {@code [null, null]}.
     */
    private static String[] csrf(HttpServletRequest request) {
        Object token = request.getAttribute("_csrf");
        if (token == null) {
            return new String[] {null, null};
        }
        try {
            // Call through the public CsrfToken interface; the implementing class is often not public.
            Class<?> api = publicInterface(token.getClass(), "org.springframework.security.web.csrf.CsrfToken");
            if (api == null) {
                return new String[] {null, null};
            }
            Method headerName = api.getMethod("getHeaderName");
            Method value = api.getMethod("getToken");
            return new String[] {(String) headerName.invoke(token), (String) value.invoke(token)};
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return new String[] {null, null};
        }
    }

    private static Class<?> publicInterface(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> candidate : current.getInterfaces()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
                Class<?> nested = publicInterface(candidate, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static byte[] readAsset(String name) {
        ClassPathResource resource = new ClassPathResource("META-INF/stethoscope/assets/" + name);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Stethoscope asset missing from the jar: " + name, ex);
        }
    }
}
