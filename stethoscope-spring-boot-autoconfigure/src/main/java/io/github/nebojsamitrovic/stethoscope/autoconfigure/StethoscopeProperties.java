package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration under the {@code stethoscope.*} prefix.
 *
 * <pre>
 * stethoscope:
 *   enabled: true            # off by default, turn on in your dev profile only
 *   path: /stethoscope
 *   allowed-ips: [127.0.0.1, "::1"]
 * </pre>
 */
@ConfigurationProperties(prefix = "stethoscope")
public class StethoscopeProperties {

    /** Master switch. Off by default so the dashboard never ships to production by accident. */
    private boolean enabled = false;

    /** Where the dashboard is served, relative to the servlet context path. */
    private String path = "/stethoscope";

    /** Maximum number of entries kept in memory per entry type; the oldest are dropped first. */
    private int maxEntries = 1000;

    /**
     * Client IPs allowed to open the dashboard. Empty list means "everyone", which you only want
     * behind your own authentication.
     */
    private List<String> allowedIps = new ArrayList<>(List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1"));

    /** Headers whose values are masked. */
    private List<String> redactHeaders = new ArrayList<>(Redactor.DEFAULT_HEADERS);

    /** Query parameters and JSON/form fields whose values are masked. */
    private List<String> redactParameters = new ArrayList<>(Redactor.DEFAULT_PARAMETERS);

    private final Requests requests = new Requests();

    private final Queries queries = new Queries();

    private final Exceptions exceptions = new Exceptions();

    private final Logs logs = new Logs();

    private final HttpClient httpClient = new HttpClient();

    private final Scheduled scheduled = new Scheduled();

    private final Events events = new Events();

    private final Cache cache = new Cache();

    private final Mail mail = new Mail();

    private final Jobs jobs = new Jobs();

    private final Models models = new Models();

    private final Security security = new Security();

    private final Messages messages = new Messages();

    private final Redis redis = new Redis();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /** Path normalized to start with '/' and have no trailing '/'. */
    public String normalizedPath() {
        String value = path == null || path.isBlank() ? "/stethoscope" : path.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    public List<String> getRedactHeaders() {
        return redactHeaders;
    }

    public void setRedactHeaders(List<String> redactHeaders) {
        this.redactHeaders = redactHeaders;
    }

    public List<String> getRedactParameters() {
        return redactParameters;
    }

    public void setRedactParameters(List<String> redactParameters) {
        this.redactParameters = redactParameters;
    }

    public Requests getRequests() {
        return requests;
    }

    public Queries getQueries() {
        return queries;
    }

    public Exceptions getExceptions() {
        return exceptions;
    }

    public Logs getLogs() {
        return logs;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public Scheduled getScheduled() {
        return scheduled;
    }

    public Events getEvents() {
        return events;
    }

    public Cache getCache() {
        return cache;
    }

    public Mail getMail() {
        return mail;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public Models getModels() {
        return models;
    }

    public Security getSecurity() {
        return security;
    }

    public Messages getMessages() {
        return messages;
    }

    public Redis getRedis() {
        return redis;
    }

    public static class Requests {

        /** Record incoming HTTP requests. */
        private boolean enabled = true;

        /** Ant-style patterns of paths that are never recorded. The dashboard itself is always ignored. */
        private List<String> ignorePaths = new ArrayList<>(List.of(
                "/actuator/**", "/favicon.ico", "/webjars/**", "/**/*.css", "/**/*.js", "/**/*.map", "/**/*.png",
                "/**/*.svg", "/**/*.ico", "/**/*.woff2"));

        /** Store the request body (text content types only, see {@link #maxBodySize}). */
        private boolean recordRequestBody = true;

        /**
         * Store the response body. Buffers the whole response in memory, so leave it off for
         * streaming / SSE / async endpoints.
         */
        private boolean recordResponseBody = false;

        /** Bodies longer than this are truncated. */
        private int maxBodySize = 64 * 1024;

        /** Requests at or above this duration are tagged {@code slow}. */
        private Duration slowThreshold = Duration.ofSeconds(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getIgnorePaths() {
            return ignorePaths;
        }

        public void setIgnorePaths(List<String> ignorePaths) {
            this.ignorePaths = ignorePaths;
        }

        public boolean isRecordRequestBody() {
            return recordRequestBody;
        }

        public void setRecordRequestBody(boolean recordRequestBody) {
            this.recordRequestBody = recordRequestBody;
        }

        public boolean isRecordResponseBody() {
            return recordResponseBody;
        }

        public void setRecordResponseBody(boolean recordResponseBody) {
            this.recordResponseBody = recordResponseBody;
        }

        public int getMaxBodySize() {
            return maxBodySize;
        }

        public void setMaxBodySize(int maxBodySize) {
            this.maxBodySize = maxBodySize;
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }
    }

    public static class Queries {

        /** Record SQL statements by wrapping every DataSource bean (requires datasource-proxy). */
        private boolean enabled = true;

        /** Store bound parameter values. */
        private boolean recordParameters = true;

        /** Queries at or above this duration are tagged {@code slow}. */
        private Duration slowThreshold = Duration.ofMillis(100);

        /** The same statement executed this many times in one request flags the request as N+1. */
        private int duplicateThreshold = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRecordParameters() {
            return recordParameters;
        }

        public void setRecordParameters(boolean recordParameters) {
            this.recordParameters = recordParameters;
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }

        public int getDuplicateThreshold() {
            return duplicateThreshold;
        }

        public void setDuplicateThreshold(int duplicateThreshold) {
            this.duplicateThreshold = duplicateThreshold;
        }
    }

    public static class Exceptions {

        /** Record exceptions thrown while handling requests, including ones turned into responses by @ExceptionHandler. */
        private boolean enabled = true;

        /** Stack frames kept per throwable in a cause chain. */
        private int maxStackTraceFrames = 40;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxStackTraceFrames() {
            return maxStackTraceFrames;
        }

        public void setMaxStackTraceFrames(int maxStackTraceFrames) {
            this.maxStackTraceFrames = maxStackTraceFrames;
        }
    }

    public static class Logs {

        /** Record log events (Logback only). */
        private boolean enabled = true;

        /** Lowest level that is recorded. */
        private String level = "INFO";

        /** Logger name prefixes that are never recorded. */
        private List<String> ignoreLoggers = new ArrayList<>();

        /** Also record exceptions attached to ERROR log events under "Exceptions". */
        private boolean recordExceptions = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public List<String> getIgnoreLoggers() {
            return ignoreLoggers;
        }

        public void setIgnoreLoggers(List<String> ignoreLoggers) {
            this.ignoreLoggers = ignoreLoggers;
        }

        public boolean isRecordExceptions() {
            return recordExceptions;
        }

        public void setRecordExceptions(boolean recordExceptions) {
            this.recordExceptions = recordExceptions;
        }
    }

    public static class HttpClient {

        /**
         * Record outgoing HTTP calls made with RestTemplate, RestClient or WebClient built from the
         * builders Spring Boot provides.
         */
        private boolean enabled = true;

        /** Store request and response bodies (text content types only). WebClient bodies are never stored. */
        private boolean recordBodies = true;

        /** Bodies longer than this are truncated. */
        private int maxBodySize = 64 * 1024;

        /** Calls at or above this duration are tagged {@code slow}. */
        private Duration slowThreshold = Duration.ofSeconds(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRecordBodies() {
            return recordBodies;
        }

        public void setRecordBodies(boolean recordBodies) {
            this.recordBodies = recordBodies;
        }

        public int getMaxBodySize() {
            return maxBodySize;
        }

        public void setMaxBodySize(int maxBodySize) {
            this.maxBodySize = maxBodySize;
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }
    }

    public static class Scheduled {

        /** Record every run of @Scheduled methods; queries, logs and exceptions of a run are linked to it. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Events {

        /** Record application events published through the ApplicationContext. */
        private boolean enabled = true;

        /** Event classes (or payload classes) in these packages are not recorded. */
        private List<String> ignorePackages = new ArrayList<>(List.of("org.springframework.",
                "io.github.nebojsamitrovic.stethoscope.core.", "io.github.nebojsamitrovic.stethoscope.autoconfigure."));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getIgnorePackages() {
            return ignorePackages;
        }

        public void setIgnorePackages(List<String> ignorePackages) {
            this.ignorePackages = ignorePackages;
        }
    }

    public static class Cache {

        /** Record cache hits, misses, puts and evictions by wrapping every CacheManager bean. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mail {

        /** Record mail sent through JavaMailSender beans. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Jobs {

        /** Record work submitted to Spring TaskExecutor beans, e.g. @Async methods; linked to the request that dispatched it. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Models {

        /** Record JPA entity inserts, updates and deletes with changed attributes (Hibernate only). */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Security {

        /** Record Spring Security authentication and authorization events. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Messages {

        /** Record Kafka and RabbitMQ messages sent and received via Spring's observation support. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Redis {

        /** Record Redis commands by wrapping every RedisConnectionFactory bean. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
