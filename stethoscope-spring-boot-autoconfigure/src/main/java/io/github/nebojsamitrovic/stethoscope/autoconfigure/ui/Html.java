package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

/** Tiny HTML helpers. Every dynamic value that goes into a page passes through {@link #esc}. */
final class Html {

    private Html() {
    }

    /** Escapes text for use in element content and quoted attribute values. */
    static String esc(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    /** {@code com.acme.OrderService} → {@code OrderService}. */
    static String simpleClassName(String className) {
        if (className == null) {
            return "";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
