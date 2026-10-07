package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Kind of thing Stethoscope recorded.
 */
public enum EntryType {

    REQUEST("Requests", "requests"),
    QUERY("Queries", "queries"),
    EXCEPTION("Exceptions", "exceptions"),
    LOG("Logs", "logs"),
    HTTP_CLIENT("HTTP Client", "http-client"),
    SCHEDULED("Schedule", "schedule"),
    EVENT("Events", "events"),
    CACHE("Cache", "cache"),
    MAIL("Mail", "mail"),
    DUMP("Dumps", "dumps");

    private final String label;
    private final String section;

    EntryType(String label, String section) {
        this.label = label;
        this.section = section;
    }

    /** Human-readable plural label, used in the UI. */
    public String label() {
        return label;
    }

    /** URL path segment of the type's dashboard page, e.g. {@code "http-client"}. */
    public String section() {
        return section;
    }

    /** URL-friendly lower-case name, e.g. {@code "request"}. */
    public String slug() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parses a slug or enum name, case-insensitively. Returns {@code null} if unknown. */
    public static EntryType fromSlug(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (EntryType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }

    /** Parses a {@link #section()}. Returns {@code null} if unknown. */
    public static EntryType fromSection(String value) {
        for (EntryType type : values()) {
            if (type.section.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
