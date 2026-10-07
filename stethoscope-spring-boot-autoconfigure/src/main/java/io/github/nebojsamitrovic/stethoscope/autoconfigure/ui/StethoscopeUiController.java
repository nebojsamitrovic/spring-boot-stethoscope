package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryQuery;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the dashboard: full pages for normal navigation, row fragments for htmx polling and
 * filtering, plus the bundled CSS and htmx script. Access is enforced by
 * {@link io.github.nebojsamitrovic.stethoscope.autoconfigure.web.RequestWatcherFilter}.
 */
@Controller
@RequestMapping("${stethoscope.path:/stethoscope}")
public class StethoscopeUiController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
    private static final int PAGE_SIZE = 100;
    private static final Map<String, MediaType> ASSETS = Map.of(
            "stethoscope.css", new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8),
            "htmx.min.js", new MediaType("text", "javascript", java.nio.charset.StandardCharsets.UTF_8));

    private final Recorder recorder;
    private final EntryStore store;
    private final StethoscopeProperties properties;
    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();

    public StethoscopeUiController(Recorder recorder, StethoscopeProperties properties) {
        this.recorder = recorder;
        this.store = recorder.store();
        this.properties = properties;
    }

    @GetMapping({"", "/"})
    public ResponseEntity<Void> index(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base(request) + "/requests"))
                .build();
    }

    @GetMapping("/{section:[a-z][a-z-]*}")
    public ResponseEntity<String> list(
            @PathVariable("section") String section,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "tag", required = false) String tag,
            HttpServletRequest request) {
        EntryType type = ViewContext.fromSection(section);
        if (type == null) {
            return notFound(request);
        }
        List<Entry> entries = store.list(new EntryQuery(type, tag, search, PAGE_SIZE));
        return html(Views.listPage(context(request), type, entries, search, tag));
    }

    @GetMapping("/{section:[a-z][a-z-]*}/rows")
    public ResponseEntity<String> rows(
            @PathVariable("section") String section,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "tag", required = false) String tag,
            HttpServletRequest request) {
        EntryType type = ViewContext.fromSection(section);
        if (type == null) {
            return notFound(request);
        }
        List<Entry> entries = store.list(new EntryQuery(type, tag, search, PAGE_SIZE));
        return html(Views.rows(context(request), type, entries));
    }

    @GetMapping("/entries/{id:\\d+}")
    public ResponseEntity<String> detail(@PathVariable("id") long id, HttpServletRequest request) {
        ViewContext ctx = context(request);
        return store.find(id)
                .map(entry -> html(Views.detail(ctx, entry, store.batch(entry.batchId()))))
                .orElseGet(() -> notFound(ctx));
    }

    @PostMapping("/clear")
    public ResponseEntity<Void> clear() {
        store.clear();
        return refresh();
    }

    @PostMapping("/pause")
    public ResponseEntity<Void> pause() {
        recorder.pause();
        return refresh();
    }

    @PostMapping("/resume")
    public ResponseEntity<Void> resume() {
        recorder.resume();
        return refresh();
    }

    @GetMapping("/assets/{name:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable("name") String name) {
        MediaType type = ASSETS.get(name);
        if (type == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = assetCache.computeIfAbsent(name, StethoscopeUiController::readAsset);
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(bytes);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> notFound(HttpServletRequest request) {
        return notFound(context(request));
    }

    private static ResponseEntity<String> notFound(ViewContext ctx) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML).body(Views.notFound(ctx));
    }

    /** Tells htmx to reload the page; plain HTML forms are not used for these actions. */
    private static ResponseEntity<Void> refresh() {
        return ResponseEntity.noContent().header("HX-Refresh", "true").build();
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok()
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private ViewContext context(HttpServletRequest request) {
        Map<EntryType, Long> counts = new EnumMap<>(EntryType.class);
        for (EntryType type : EntryType.values()) {
            counts.put(type, store.count(type));
        }
        String[] csrf = csrf(request);
        return new ViewContext(base(request), csrf[0], csrf[1], counts, recorder.isRecording(), Instant.now(),
                ZoneId.systemDefault());
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
