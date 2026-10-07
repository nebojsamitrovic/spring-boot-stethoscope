package io.github.nebojsamitrovic.stethoscope.autoconfigure.http;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.Bodies;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/** Turns one outgoing HTTP exchange into an {@link EntryType#HTTP_CLIENT} entry. Shared by all client integrations. */
public class HttpClientRecorder {

    private final Recorder recorder;
    private final Redactor redactor;
    private final StethoscopeProperties.HttpClient settings;

    public HttpClientRecorder(Recorder recorder, Redactor redactor, StethoscopeProperties.HttpClient settings) {
        this.recorder = recorder;
        this.redactor = redactor;
        this.settings = settings;
    }

    public boolean isRecording() {
        return recorder.isRecording();
    }

    public boolean recordBodies() {
        return settings.isRecordBodies();
    }

    public int maxBodySize() {
        return settings.getMaxBodySize();
    }

    /** One finished (or failed) exchange. Fields that are unknown are {@code null}. */
    public record Exchange(
            String method,
            URI uri,
            HttpHeaders requestHeaders,
            String requestBody,
            Integer status,
            HttpHeaders responseHeaders,
            String responseBody,
            Throwable error,
            long durationNanos) {
    }

    /**
     * @param batchId batch to attach to; pass the id captured when the call started, since reactive
     *                clients may complete on another thread
     */
    public void record(Exchange exchange, String batchId) {
        try {
            long durationMs = exchange.durationNanos() / 1_000_000;
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.METHOD, exchange.method());
            content.put(Entry.Content.URL, url(exchange.uri()));
            if (exchange.status() != null) {
                content.put(Entry.Content.STATUS, exchange.status());
            }
            content.put(Entry.Content.DURATION_MS, durationMs);
            if (exchange.error() != null) {
                content.put(Entry.Content.ERROR, exchange.error().getClass().getName() + ": " + exchange.error().getMessage());
            }
            content.put(Entry.Content.REQUEST_HEADERS, redactor.headers(flatten(exchange.requestHeaders())));
            if (exchange.requestBody() != null && !exchange.requestBody().isEmpty()) {
                content.put(Entry.Content.REQUEST_BODY, redactor.body(exchange.requestBody()));
            }
            if (exchange.responseHeaders() != null) {
                content.put(Entry.Content.RESPONSE_HEADERS, redactor.headers(flatten(exchange.responseHeaders())));
            }
            if (exchange.responseBody() != null && !exchange.responseBody().isEmpty()) {
                content.put(Entry.Content.RESPONSE_BODY, redactor.body(exchange.responseBody()));
            }

            Set<String> tags = new HashSet<>();
            if (exchange.status() != null) {
                tags.add(Entry.Tags.status(exchange.status()));
            }
            if (exchange.error() != null || (exchange.status() != null && exchange.status() >= 500)) {
                tags.add(Entry.Tags.FAILED);
            }
            if (durationMs >= settings.getSlowThreshold().toMillis()) {
                tags.add(Entry.Tags.SLOW);
            }
            recorder.record(EntryType.HTTP_CLIENT, content, tags, batchId);
        } catch (RuntimeException ignored) {
            // never break the call because of the debugger
        }
    }

    /** Request body as text, or {@code null} when it is empty, binary or bodies are off. */
    public String requestBody(byte[] body, HttpHeaders headers) {
        if (!settings.isRecordBodies() || body == null || body.length == 0 || headers == null
                || !Bodies.isText(String.valueOf(headers.getContentType()))) {
            return null;
        }
        return Bodies.toText(body, body.length, charset(headers), settings.getMaxBodySize(), body.length);
    }

    static String charset(HttpHeaders headers) {
        if (headers == null || headers.getContentType() == null || headers.getContentType().getCharset() == null) {
            return null;
        }
        return headers.getContentType().getCharset().name();
    }

    private String url(URI uri) {
        if (uri == null) {
            return "";
        }
        String raw = uri.toString();
        String query = uri.getRawQuery();
        if (query == null) {
            return raw;
        }
        int at = raw.indexOf('?');
        String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
        return raw.substring(0, at) + "?" + redactor.queryString(query) + fragment;
    }

    private static Map<String, String> flatten(HttpHeaders headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> result.put(name, String.join(", ", values)));
        }
        return result;
    }
}
