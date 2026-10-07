package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Kind of thing Stethoscope recorded.
 */
public enum EntryType {

    REQUEST("Requests"),
    QUERY("Queries"),
    EXCEPTION("Exceptions");

    private final String label;

    EntryType(String label) {
        this.label = label;
    }

    /** Human-readable plural label, used in the UI. */
    public String label() {
        return label;
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
}
