package io.github.nebojsamitrovic.stethoscope.autoconfigure.support;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Helpers for deciding whether a payload is text and turning captured bytes into a bounded string. */
public final class Bodies {

    private static final Set<String> TEXT_SUBTYPES = Set.of("json", "xml", "x-www-form-urlencoded", "javascript",
            "graphql", "problem+json", "hal+json", "x-ndjson");

    private Bodies() {
    }

    /** {@code true} for {@code text/*}, JSON, XML, form data and similar content types. */
    public static boolean isText(String contentType) {
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

    /**
     * Decodes at most {@code max} bytes.
     *
     * @param totalLength full payload size if known (for the truncation note), otherwise {@code -1}
     */
    public static String toText(byte[] bytes, int length, String encoding, int max, long totalLength) {
        int shown = Math.min(length, max);
        String text = new String(bytes, 0, shown, charset(encoding));
        if (length > max) {
            return text + "\n… (truncated" + (totalLength > 0 ? ", " + totalLength + " bytes total" : "") + ")";
        }
        return text;
    }

    public static Charset charset(String encoding) {
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Single value rendered for display, cut at {@code max} characters. */
    public static String abbreviate(Object value, int max) {
        String text = String.valueOf(value);
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
