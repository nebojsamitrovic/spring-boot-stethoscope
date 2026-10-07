package io.github.nebojsamitrovic.stethoscope.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One recorded event: an HTTP request, a SQL query, an exception...
 *
 * <p>Everything recorded during the same HTTP request shares a {@code batchId}, so the UI can show
 * "this request ran these 12 queries and threw this exception".
 *
 * @param id        monotonically increasing id, unique within the running JVM
 * @param batchId   id of the batch (usually one HTTP request) this entry belongs to; may be {@code null}
 * @param type      what kind of entry this is
 * @param createdAt when it was recorded
 * @param content   type-specific payload; see {@link Content} for well-known keys
 * @param tags      free-form labels used for filtering, e.g. {@code "slow"}, {@code "n+1"}, {@code "status:500"}
 */
public record Entry(
        long id,
        String batchId,
        EntryType type,
        Instant createdAt,
        Map<String, Object> content,
        Set<String> tags) {

    public Entry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        content = content == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(content));
        tags = tags == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(tags));
    }

    /** Returns a content value, or {@code null} when absent. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) content.get(key);
    }

    /** Returns a content value as a string, or {@code fallback} when absent. */
    public String getString(String key, String fallback) {
        Object value = content.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** Returns a numeric content value as long, or {@code fallback} when absent or not a number. */
    public long getLong(String key, long fallback) {
        Object value = content.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** Well-known content keys. Kept as constants so watchers and the UI agree on names. */
    public static final class Content {

        // request
        public static final String METHOD = "method";
        public static final String URI = "uri";
        public static final String QUERY_STRING = "queryString";
        public static final String STATUS = "status";
        public static final String DURATION_MS = "durationMs";
        public static final String CLIENT_IP = "clientIp";
        public static final String REQUEST_HEADERS = "requestHeaders";
        public static final String RESPONSE_HEADERS = "responseHeaders";
        public static final String REQUEST_BODY = "requestBody";
        public static final String RESPONSE_BODY = "responseBody";
        public static final String HANDLER = "handler";
        public static final String QUERY_COUNT = "queryCount";
        public static final String QUERY_TIME_MS = "queryTimeMs";
        public static final String DUPLICATE_QUERIES = "duplicateQueries";

        // query
        public static final String SQL = "sql";
        public static final String PARAMETERS = "parameters";
        public static final String DATA_SOURCE = "dataSource";
        public static final String SUCCESS = "success";

        // exception
        public static final String EXCEPTION_CLASS = "class";
        public static final String MESSAGE = "message";
        public static final String LOCATION = "location";
        public static final String STACK_TRACE = "stackTrace";
        public static final String HANDLED = "handled";

        // log (also MESSAGE, STACK_TRACE)
        public static final String LEVEL = "level";
        public static final String LOGGER = "logger";
        public static final String THREAD = "thread";
        public static final String MDC = "mdc";

        // outgoing HTTP (also METHOD, STATUS, DURATION_MS, *_HEADERS, *_BODY)
        public static final String URL = "url";
        public static final String ERROR = "error";

        // scheduled task (also DURATION_MS, SUCCESS, ERROR, THREAD)
        public static final String TASK = "task";

        // application event
        public static final String EVENT_CLASS = "eventClass";
        public static final String PAYLOAD = "payload";
        public static final String SOURCE = "source";

        // cache
        public static final String CACHE_NAME = "cache";
        public static final String OPERATION = "operation";
        public static final String KEY = "key";
        public static final String VALUE = "value";

        // mail (also ERROR)
        public static final String FROM = "from";
        public static final String TO = "to";
        public static final String CC = "cc";
        public static final String BCC = "bcc";
        public static final String SUBJECT = "subject";
        public static final String TEXT_BODY = "text";
        public static final String HTML_BODY = "html";
        public static final String ATTACHMENTS = "attachments";

        // dump (also LOCATION)
        public static final String VALUES = "values";

        private Content() {
        }
    }

    /** Well-known tags. */
    public static final class Tags {

        public static final String SLOW = "slow";
        public static final String N_PLUS_ONE = "n+1";
        public static final String FAILED = "failed";
        public static final String HAS_EXCEPTION = "exception";

        public static String status(int status) {
            return "status:" + status;
        }

        private Tags() {
        }
    }
}
