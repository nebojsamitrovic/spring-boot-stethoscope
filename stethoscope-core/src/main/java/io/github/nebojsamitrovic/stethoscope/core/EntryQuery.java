package io.github.nebojsamitrovic.stethoscope.core;

/**
 * Filter for listing entries. All fields are optional.
 *
 * @param type   only entries of this type
 * @param tag    only entries carrying this tag
 * @param search case-insensitive substring match on the entry's main text (URI, SQL, exception class/message)
 * @param limit  maximum number of results (newest first); values below 1 mean "use default"
 */
public record EntryQuery(EntryType type, String tag, String search, int limit) {

    public static final int DEFAULT_LIMIT = 50;

    public EntryQuery {
        tag = blankToNull(tag);
        search = blankToNull(search);
        limit = limit < 1 ? DEFAULT_LIMIT : limit;
    }

    public static EntryQuery of(EntryType type) {
        return new EntryQuery(type, null, null, DEFAULT_LIMIT);
    }

    boolean matches(Entry entry) {
        if (type != null && entry.type() != type) {
            return false;
        }
        if (tag != null && !entry.hasTag(tag)) {
            return false;
        }
        if (search != null) {
            String haystack = searchableText(entry).toLowerCase(java.util.Locale.ROOT);
            return haystack.contains(search.toLowerCase(java.util.Locale.ROOT));
        }
        return true;
    }

    static String searchableText(Entry entry) {
        return switch (entry.type()) {
            case REQUEST -> entry.getString(Entry.Content.METHOD, "") + " " + entry.getString(Entry.Content.URI, "");
            case QUERY -> entry.getString(Entry.Content.SQL, "");
            case EXCEPTION -> entry.getString(Entry.Content.EXCEPTION_CLASS, "") + " "
                    + entry.getString(Entry.Content.MESSAGE, "");
            case LOG -> entry.getString(Entry.Content.LOGGER, "") + " " + entry.getString(Entry.Content.MESSAGE, "");
            case HTTP_CLIENT -> entry.getString(Entry.Content.METHOD, "") + " " + entry.getString(Entry.Content.URL, "");
            case SCHEDULED -> entry.getString(Entry.Content.TASK, "");
            case EVENT -> entry.getString(Entry.Content.EVENT_CLASS, "") + " " + entry.getString(Entry.Content.PAYLOAD, "");
            case CACHE -> entry.getString(Entry.Content.CACHE_NAME, "") + " " + entry.getString(Entry.Content.KEY, "");
            case MAIL -> entry.getString(Entry.Content.SUBJECT, "") + " " + entry.getString(Entry.Content.TO, "");
            case DUMP -> entry.getString(Entry.Content.VALUES, "");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
