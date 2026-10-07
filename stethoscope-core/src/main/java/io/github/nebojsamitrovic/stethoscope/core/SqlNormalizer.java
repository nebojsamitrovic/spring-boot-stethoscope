package io.github.nebojsamitrovic.stethoscope.core;

import java.util.regex.Pattern;

/**
 * Reduces a SQL statement to its "shape" so that the same query with different literal values
 * counts as a repeat. Used for N+1 detection.
 *
 * <pre>
 * select * from post where author_id = 42   →  select * from post where author_id = ?
 * select * from post where author_id = ?    →  select * from post where author_id = ?
 * ... where id in (1, 2, 3)                 →  ... where id in (?)
 * </pre>
 */
public final class SqlNormalizer {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlNormalizer() {
    }

    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String result = STRING_LITERAL.matcher(sql).replaceAll("?");
        result = NUMBER_LITERAL.matcher(result).replaceAll("?");
        result = IN_LIST.matcher(result).replaceAll("in (?)");
        result = WHITESPACE.matcher(result).replaceAll(" ");
        return result.trim();
    }
}
