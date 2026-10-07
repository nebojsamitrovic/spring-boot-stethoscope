package io.github.nebojsamitrovic.stethoscope.autoconfigure.web;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeGate;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/**
 * Records every HTTP request as one {@link EntryType#REQUEST} entry and opens a {@link Batch}
 * so queries and exceptions recorded during the request are linked to it.
 *
 * <p>Also guards the dashboard path with the {@link StethoscopeGate}, so the gate holds even if
 * the UI controller is reached by some other route.
 */
public class RequestWatcherFilter extends OncePerRequestFilter implements Ordered {

    /** Runs early, but leaves room for filters that must run before it (e.g. request-id filters). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Set<String> TEXT_SUBTYPES = Set.of("json", "xml", "x-www-form-urlencoded", "javascript",
            "graphql", "problem+json", "hal+json", "x-ndjson");

    private final Recorder recorder;
    private final Redactor redactor;
    private final StethoscopeProperties properties;
    private final StethoscopeGate gate;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RequestWatcherFilter(
            Recorder recorder, Redactor redactor, StethoscopeProperties properties, StethoscopeGate gate) {
        this.recorder = recorder;
        this.redactor = redactor;
        this.properties = properties;
        this.gate = gate;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Async dispatches pass through so a buffered response body can be flushed (see doFilterInternal). */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            // The request was already recorded when the async processing started. If its response is
            // buffered, flush it now that the async result has been written.
            chain.doFilter(request, response);
            ContentCachingResponseWrapper wrapper =
                    WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
            if (wrapper != null && !request.isAsyncStarted()) {
                wrapper.copyBodyToResponse();
            }
            return;
        }

        String path = pathWithinApplication(request);

        if (isDashboard(path)) {
            if (!gate.allows(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Stethoscope: access denied");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (!properties.getRequests().isEnabled() || !recorder.isRecording() || isIgnored(path)) {
            chain.doFilter(request, response);
            return;
        }

        StethoscopeProperties.Requests settings = properties.getRequests();
        HttpServletRequest requestToUse = settings.isRecordRequestBody()
                ? new ContentCachingRequestWrapper(request, settings.getMaxBodySize())
                : request;
        ContentCachingResponseWrapper responseWrapper = settings.isRecordResponseBody() && !isStreaming(request)
                ? new ContentCachingResponseWrapper(response)
                : null;
        HttpServletResponse responseToUse = responseWrapper != null ? responseWrapper : response;

        Batch batch = BatchContext.start();
        Throwable failure = null;
        try {
            chain.doFilter(requestToUse, responseToUse);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                record(requestToUse, responseToUse, responseWrapper, batch, failure);
            } finally {
                BatchContext.end();
                if (responseWrapper != null && !requestToUse.isAsyncStarted()) {
                    responseWrapper.copyBodyToResponse();
                }
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response,
            ContentCachingResponseWrapper responseWrapper, Batch batch, Throwable failure) {
        try {
            long durationMs = (System.nanoTime() - batch.startedAtNanos()) / 1_000_000;

            if (properties.getExceptions().isEnabled()) {
                Object handled = request.getAttribute(ExceptionCapturingResolver.EXCEPTION_ATTRIBUTE);
                if (failure != null) {
                    recorder.recordException(unwrap(failure), false);
                } else if (handled instanceof Throwable throwable) {
                    recorder.recordException(throwable, true);
                }
            }

            int status = failure != null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.METHOD, request.getMethod());
            content.put(Entry.Content.URI, request.getRequestURI());
            if (request.getQueryString() != null) {
                content.put(Entry.Content.QUERY_STRING, redactor.queryString(request.getQueryString()));
            }
            content.put(Entry.Content.STATUS, status);
            content.put(Entry.Content.DURATION_MS, durationMs);
            content.put(Entry.Content.CLIENT_IP, request.getRemoteAddr());
            String handler = handlerName(request);
            if (handler != null) {
                content.put(Entry.Content.HANDLER, handler);
            }
            content.put(Entry.Content.REQUEST_HEADERS, redactor.headers(requestHeaders(request)));
            content.put(Entry.Content.RESPONSE_HEADERS, redactor.headers(responseHeaders(response)));

            ContentCachingRequestWrapper cachingRequest =
                    WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
            if (cachingRequest != null && isText(request.getContentType())) {
                String body = toText(cachingRequest.getContentAsByteArray(), request.getCharacterEncoding());
                if (!body.isEmpty()) {
                    content.put(Entry.Content.REQUEST_BODY, redactor.body(body));
                }
            }
            if (responseWrapper != null && isText(response.getContentType())) {
                String body = toText(responseWrapper.getContentAsByteArray(), response.getCharacterEncoding());
                if (!body.isEmpty()) {
                    content.put(Entry.Content.RESPONSE_BODY, redactor.body(body));
                }
            }

            Set<String> tags = new HashSet<>();
            tags.add(Entry.Tags.status(status));
            if (durationMs >= properties.getRequests().getSlowThreshold().toMillis()) {
                tags.add(Entry.Tags.SLOW);
            }
            if (status >= 500) {
                tags.add(Entry.Tags.FAILED);
            }
            recorder.applyBatchSummary(batch, content, tags);
            recorder.record(EntryType.REQUEST, content, tags);
        } catch (RuntimeException ignored) {
            // the debugger must never break the request it is observing
        }
    }

    private boolean isDashboard(String path) {
        String base = properties.normalizedPath();
        return path.equals(base) || path.startsWith(base + "/");
    }

    private boolean isIgnored(String path) {
        List<String> patterns = properties.getRequests().getIgnorePaths();
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private static boolean isStreaming(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private static String handlerName(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod method) {
            return method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
        }
        return handler == null ? null : handler.getClass().getSimpleName();
    }

    private static Map<String, String> requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, String.join(", ", java.util.Collections.list(request.getHeaders(name))));
        }
        return headers;
    }

    private static Map<String, String> responseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : new java.util.LinkedHashSet<>(response.getHeaderNames())) {
            Collection<String> values = response.getHeaders(name);
            headers.put(name, String.join(", ", values));
        }
        if (response.getContentType() != null && !headers.containsKey("Content-Type")) {
            headers.put("Content-Type", response.getContentType());
        }
        return headers;
    }

    static boolean isText(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon);
        }
        type = type.trim();
        if (type.startsWith("text/")) {
            return true;
        }
        int slash = type.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String subtype = type.substring(slash + 1);
        return TEXT_SUBTYPES.contains(subtype) || subtype.endsWith("+json") || subtype.endsWith("+xml");
    }

    private String toText(byte[] bytes, String encoding) {
        int max = properties.getRequests().getMaxBodySize();
        int length = Math.min(bytes.length, max);
        Charset charset;
        try {
            charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException ex) {
            charset = StandardCharsets.UTF_8;
        }
        String text = new String(bytes, 0, length, charset);
        return bytes.length > max ? text + "\n… (truncated, " + bytes.length + " bytes total)" : text;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof ServletException servletException && servletException.getRootCause() != null) {
            return servletException.getRootCause();
        }
        return failure;
    }
}
