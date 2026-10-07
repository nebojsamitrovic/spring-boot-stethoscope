package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * Per-request data the views need.
 *
 * @param base       absolute path of the dashboard, including the servlet context path
 * @param csrfHeader CSRF header name when Spring Security provides a token, otherwise {@code null}
 * @param csrfToken  CSRF token value, otherwise {@code null}
 * @param counts     stored entries per type, for the tab badges
 * @param recording  whether the recorder is currently recording
 * @param now        reference time for "12s ago"
 * @param zone       time zone used to show absolute times
 */
record ViewContext(
        String base,
        String csrfHeader,
        String csrfToken,
        Map<EntryType, Long> counts,
        boolean recording,
        Instant now,
        ZoneId zone) {

    String link(String path) {
        return base + path;
    }

    static String section(EntryType type) {
        return type.section();
    }

    static EntryType fromSection(String section) {
        return EntryType.fromSection(section);
    }
}
