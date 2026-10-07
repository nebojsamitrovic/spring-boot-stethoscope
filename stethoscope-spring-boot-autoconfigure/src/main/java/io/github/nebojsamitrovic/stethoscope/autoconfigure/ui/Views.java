package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.esc;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.simpleClassName;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.truncate;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-rendered pages and fragments. Plain strings, no template engine, so the library adds
 * nothing to the host application's classpath. htmx does the rest in the browser.
 */
final class Views {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String LOGO = """
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" \
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">\
            <path d="M5 3v5a5 5 0 0 0 10 0V3"/><path d="M10 13v3a5 5 0 0 0 10 0v-2"/><circle cx="20" cy="12" r="2"/></svg>""";

    private Views() {
    }

    // ---------------------------------------------------------------- layout

    static String page(ViewContext ctx, EntryType active, String title, String body) {
        StringBuilder tabs = new StringBuilder();
        for (EntryType type : EntryType.values()) {
            tabs.append("<a class=\"tab").append(type == active ? " active" : "").append("\" href=\"")
                    .append(esc(ctx.link("/" + ViewContext.section(type)))).append("\">")
                    .append(esc(type.label()))
                    .append("<span class=\"count\">").append(ctx.counts().getOrDefault(type, 0L)).append("</span></a>");
        }

        String hxHeaders = ctx.csrfHeader() == null ? ""
                : " hx-headers=\"" + esc("{\"" + jsonEscape(ctx.csrfHeader()) + "\":\"" + jsonEscape(ctx.csrfToken()) + "\"}") + "\"";

        String recording = ctx.recording()
                ? "<span class=\"rec on\">Recording</span>"
                  + "<button class=\"btn\" hx-post=\"" + esc(ctx.link("/pause")) + "\">Pause</button>"
                : "<span class=\"rec off\">Paused</span>"
                  + "<button class=\"btn\" hx-post=\"" + esc(ctx.link("/resume")) + "\">Resume</button>";

        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="htmx-config" content='{"includeIndicatorStyles":false}'>
                <title>%s · Stethoscope</title>
                <link rel="stylesheet" href="%s">
                <script src="%s" defer></script>
                </head>
                <body%s>
                <header class="topbar">
                  <a class="brand" href="%s">%s<span>Stethoscope</span></a>
                  <nav class="tabs">%s</nav>
                  <div class="actions">%s<button class="btn danger" hx-post="%s" hx-confirm="Clear all recorded entries?">Clear</button></div>
                </header>
                <main>
                %s
                </main>
                </body>
                </html>
                """.formatted(
                esc(title),
                esc(ctx.link("/assets/stethoscope.css")),
                esc(ctx.link("/assets/htmx.min.js")),
                hxHeaders,
                esc(ctx.link("/requests")), LOGO,
                tabs,
                recording,
                esc(ctx.link("/clear")),
                body);
    }

    // ---------------------------------------------------------------- list pages

    static String listPage(ViewContext ctx, EntryType type, List<Entry> entries, String search, String tag) {
        String section = ViewContext.section(type);
        String rowsUrl = ctx.link("/" + section + "/rows");

        StringBuilder options = new StringBuilder("<option value=\"\">All</option>");
        for (String[] option : tagOptions(type)) {
            options.append("<option value=\"").append(esc(option[0])).append("\"")
                    .append(option[0].equals(tag) ? " selected" : "").append(">")
                    .append(esc(option[1])).append("</option>");
        }

        String body = """
                <div class="toolbar">
                  <form id="filters" class="filters" action="%s" method="get"
                        hx-get="%s" hx-target="#rows" hx-trigger="input changed delay:300ms, change">
                    <input type="search" name="q" value="%s" placeholder="%s" aria-label="Search" autocomplete="off">
                    <select name="tag" aria-label="Filter">%s</select>
                  </form>
                  <label class="live"><input type="checkbox" id="live" checked> Live</label>
                </div>
                <div class="table-wrap">
                <table class="entries %s">
                  <thead><tr>%s</tr></thead>
                  <tbody id="rows" hx-get="%s" hx-include="#filters"
                         hx-trigger="every 3s [document.getElementById('live').checked]">
                %s
                  </tbody>
                </table>
                </div>
                """.formatted(
                esc(ctx.link("/" + section)),
                esc(rowsUrl),
                esc(search),
                esc(searchPlaceholder(type)),
                options,
                section,
                headerCells(type),
                esc(rowsUrl),
                rows(ctx, type, entries));
        return page(ctx, type, type.label(), body);
    }

    static String rows(ViewContext ctx, EntryType type, List<Entry> entries) {
        if (entries.isEmpty()) {
            return "<tr class=\"empty\"><td colspan=\"" + columnCount(type) + "\">"
                   + "Nothing here yet. Use your app and entries will appear as they happen.</td></tr>";
        }
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(switch (type) {
                case REQUEST -> requestRow(ctx, entry);
                case QUERY -> queryRow(ctx, entry);
                case EXCEPTION -> exceptionRow(ctx, entry);
            });
        }
        return out.toString();
    }

    private static String requestRow(ViewContext ctx, Entry e) {
        String uri = e.getString(Entry.Content.URI, "");
        String query = e.getString(Entry.Content.QUERY_STRING, null);
        int status = (int) e.getLong(Entry.Content.STATUS, 0);
        long queries = e.getLong(Entry.Content.QUERY_COUNT, 0);
        return "<tr>"
               + "<td>" + methodBadge(e.getString(Entry.Content.METHOD, "")) + "</td>"
               + "<td class=\"main\"><a class=\"row-link\" href=\"" + esc(ctx.link("/entries/" + e.id())) + "\">"
               + esc(uri) + (query == null ? "" : "<span class=\"muted\">?" + esc(truncate(query, 60)) + "</span>")
               + "</a>" + (e.hasTag(Entry.Tags.HAS_EXCEPTION) ? " <span class=\"badge red\">exception</span>" : "")
               + "</td>"
               + "<td>" + statusBadge(status) + "</td>"
               + "<td class=\"num" + (e.hasTag(Entry.Tags.SLOW) ? " warn" : "") + "\">" + e.getLong(Entry.Content.DURATION_MS, 0) + " ms</td>"
               + "<td class=\"num\">" + queries
               + (e.hasTag(Entry.Tags.N_PLUS_ONE) ? " <span class=\"badge amber\">N+1</span>" : "") + "</td>"
               + "<td class=\"when\">" + when(ctx, e) + "</td>"
               + "</tr>\n";
    }

    private static String queryRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\"><a class=\"row-link code\" href=\"" + esc(ctx.link("/entries/" + e.id())) + "\">"
               + esc(truncate(e.getString(Entry.Content.SQL, ""), 160)) + "</a>"
               + (e.hasTag(Entry.Tags.FAILED) ? " <span class=\"badge red\">failed</span>" : "")
               + "</td>"
               + "<td class=\"num" + (e.hasTag(Entry.Tags.SLOW) ? " warn" : "") + "\">" + e.getLong(Entry.Content.DURATION_MS, 0) + " ms"
               + (e.hasTag(Entry.Tags.SLOW) ? " <span class=\"badge amber\">slow</span>" : "") + "</td>"
               + "<td class=\"when\">" + when(ctx, e) + "</td>"
               + "</tr>\n";
    }

    private static String exceptionRow(ViewContext ctx, Entry e) {
        boolean handled = Boolean.TRUE.equals(e.get(Entry.Content.HANDLED));
        return "<tr>"
               + "<td class=\"main\"><a class=\"row-link\" href=\"" + esc(ctx.link("/entries/" + e.id())) + "\">"
               + "<strong>" + esc(simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, ""))) + "</strong>"
               + " <span class=\"muted\">" + esc(truncate(e.getString(Entry.Content.MESSAGE, ""), 120)) + "</span></a></td>"
               + "<td class=\"code small\">" + esc(shortLocation(e.getString(Entry.Content.LOCATION, ""))) + "</td>"
               + "<td>" + (handled ? "<span class=\"badge\">handled</span>" : "<span class=\"badge red\">unhandled</span>") + "</td>"
               + "<td class=\"when\">" + when(ctx, e) + "</td>"
               + "</tr>\n";
    }

    // ---------------------------------------------------------------- detail pages

    static String detail(ViewContext ctx, Entry entry, List<Entry> batch) {
        String body = switch (entry.type()) {
            case REQUEST -> requestDetail(ctx, entry, batch);
            case QUERY -> queryDetail(ctx, entry, batch);
            case EXCEPTION -> exceptionDetail(ctx, entry, batch);
        };
        return page(ctx, entry.type(), title(entry), body);
    }

    private static String requestDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        int status = (int) e.getLong(Entry.Content.STATUS, 0);
        String query = e.getString(Entry.Content.QUERY_STRING, null);
        List<Entry> queries = batch.stream().filter(b -> b.type() == EntryType.QUERY).toList();
        List<Entry> exceptions = batch.stream().filter(b -> b.type() == EntryType.EXCEPTION).toList();

        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.REQUEST));
        out.append("<div class=\"detail-head\">").append(methodBadge(e.getString(Entry.Content.METHOD, "")))
                .append("<h1 class=\"code\">").append(esc(e.getString(Entry.Content.URI, "")))
                .append(query == null ? "" : "<span class=\"muted\">?" + esc(query) + "</span>")
                .append("</h1>").append(statusBadge(status)).append("</div>");

        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                "Handler", esc(e.getString(Entry.Content.HANDLER, "—")),
                "Client IP", esc(e.getString(Entry.Content.CLIENT_IP, "—")),
                "Queries", e.getLong(Entry.Content.QUERY_COUNT, 0) + " · " + e.getLong(Entry.Content.QUERY_TIME_MS, 0) + " ms"));

        Map<String, Integer> duplicates = e.get(Entry.Content.DUPLICATE_QUERIES);
        if (duplicates != null && !duplicates.isEmpty()) {
            out.append("<div class=\"callout amber\"><strong>Possible N+1.</strong> These statements ran repeatedly in one request. "
                       + "Consider a join fetch, an entity graph or batch loading.<ul>");
            duplicates.forEach((sql, count) -> out.append("<li><span class=\"times\">× ").append(count)
                    .append("</span><code>").append(esc(truncate(sql, 300))).append("</code></li>"));
            out.append("</ul></div>");
        }

        if (!exceptions.isEmpty()) {
            out.append("<section><h2>Exceptions</h2><ul class=\"links\">");
            for (Entry ex : exceptions) {
                out.append("<li><a href=\"").append(esc(ctx.link("/entries/" + ex.id()))).append("\"><strong>")
                        .append(esc(simpleClassName(ex.getString(Entry.Content.EXCEPTION_CLASS, ""))))
                        .append("</strong> <span class=\"muted\">")
                        .append(esc(truncate(ex.getString(Entry.Content.MESSAGE, ""), 160)))
                        .append("</span></a></li>");
            }
            out.append("</ul></section>");
        }

        out.append("<section><h2>Queries <span class=\"count\">").append(queries.size()).append("</span></h2>");
        if (queries.isEmpty()) {
            out.append("<p class=\"muted\">No queries were recorded for this request.</p>");
        } else {
            out.append("<div class=\"table-wrap\"><table class=\"entries\"><thead><tr><th>Query</th><th class=\"num\">Duration</th></tr></thead><tbody>");
            for (Entry q : queries) {
                out.append("<tr><td class=\"main\"><a class=\"row-link code\" href=\"").append(esc(ctx.link("/entries/" + q.id())))
                        .append("\">").append(esc(truncate(q.getString(Entry.Content.SQL, ""), 200))).append("</a></td>")
                        .append("<td class=\"num").append(q.hasTag(Entry.Tags.SLOW) ? " warn" : "").append("\">")
                        .append(q.getLong(Entry.Content.DURATION_MS, 0)).append(" ms</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        out.append("</section>");

        out.append(keyValueSection("Request headers", e.get(Entry.Content.REQUEST_HEADERS)));
        out.append(bodySection("Request body", e.getString(Entry.Content.REQUEST_BODY, null)));
        out.append(keyValueSection("Response headers", e.get(Entry.Content.RESPONSE_HEADERS)));
        out.append(bodySection("Response body", e.getString(Entry.Content.RESPONSE_BODY, null)));
        return out.toString();
    }

    private static String queryDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.QUERY));
        out.append("<div class=\"detail-head\"><h1>Query</h1>");
        if (e.hasTag(Entry.Tags.SLOW)) {
            out.append("<span class=\"badge amber\">slow</span>");
        }
        if (e.hasTag(Entry.Tags.FAILED)) {
            out.append("<span class=\"badge red\">failed</span>");
        }
        out.append("</div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                "Data source", esc(e.getString(Entry.Content.DATA_SOURCE, "—")),
                "Request", requestLink(ctx, batch)));
        out.append("<section><h2>SQL</h2><pre class=\"block sql\">").append(esc(e.getString(Entry.Content.SQL, "")))
                .append("</pre></section>");

        List<String> parameters = e.get(Entry.Content.PARAMETERS);
        if (parameters != null && !parameters.isEmpty()) {
            out.append("<section><h2>Parameters</h2><ol class=\"params\">");
            for (String parameter : parameters) {
                out.append("<li><code>").append(esc(parameter)).append("</code></li>");
            }
            out.append("</ol></section>");
        }
        return out.toString();
    }

    private static String exceptionDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        boolean handled = Boolean.TRUE.equals(e.get(Entry.Content.HANDLED));
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.EXCEPTION));
        out.append("<div class=\"detail-head\"><h1>")
                .append(esc(simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, ""))))
                .append("</h1>")
                .append(handled ? "<span class=\"badge\">handled</span>" : "<span class=\"badge red\">unhandled</span>")
                .append("</div>");
        String message = e.getString(Entry.Content.MESSAGE, null);
        if (message != null) {
            out.append("<p class=\"message\">").append(esc(message)).append("</p>");
        }
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Class", "<span class=\"code\">" + esc(e.getString(Entry.Content.EXCEPTION_CLASS, "")) + "</span>",
                "Location", "<span class=\"code\">" + esc(e.getString(Entry.Content.LOCATION, "—")) + "</span>",
                "Request", requestLink(ctx, batch)));
        out.append("<section><h2>Stack trace</h2><pre class=\"block\">")
                .append(esc(e.getString(Entry.Content.STACK_TRACE, "")))
                .append("</pre></section>");
        return out.toString();
    }

    static String notFound(ViewContext ctx) {
        return page(ctx, null, "Not found", backLink(ctx, EntryType.REQUEST)
                + "<div class=\"callout\"><strong>Entry not found.</strong> It may have been evicted "
                + "(only the newest entries are kept) or cleared.</div>");
    }

    // ---------------------------------------------------------------- pieces

    private static String backLink(ViewContext ctx, EntryType type) {
        return "<a class=\"back\" href=\"" + esc(ctx.link("/" + ViewContext.section(type))) + "\">← "
               + esc(type.label()) + "</a>";
    }

    private static String requestLink(ViewContext ctx, List<Entry> batch) {
        Optional<Entry> request = batch.stream().filter(b -> b.type() == EntryType.REQUEST).findFirst();
        return request.map(r -> "<a href=\"" + esc(ctx.link("/entries/" + r.id())) + "\">"
                                 + esc(r.getString(Entry.Content.METHOD, "")) + " "
                                 + esc(r.getString(Entry.Content.URI, "")) + "</a>")
                .orElse("—");
    }

    /** Alternating label/value pairs. Values are already-escaped HTML. */
    private static String facts(String... pairs) {
        StringBuilder out = new StringBuilder("<dl class=\"facts\">");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.append("<div><dt>").append(esc(pairs[i])).append("</dt><dd>").append(pairs[i + 1]).append("</dd></div>");
        }
        return out.append("</dl>").toString();
    }

    private static String keyValueSection(String title, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<section><h2>").append(esc(title)).append("</h2><table class=\"kv\"><tbody>");
        values.forEach((key, value) -> out.append("<tr><th>").append(esc(key)).append("</th><td class=\"code\">")
                .append(esc(value)).append("</td></tr>"));
        return out.append("</tbody></table></section>").toString();
    }

    private static String bodySection(String title, String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return "<section><h2>" + esc(title) + "</h2><pre class=\"block\">" + esc(body) + "</pre></section>";
    }

    private static String methodBadge(String method) {
        return "<span class=\"method m-" + esc(method.toLowerCase(java.util.Locale.ROOT)) + "\">" + esc(method) + "</span>";
    }

    private static String statusBadge(int status) {
        String tone = status >= 500 ? "red" : status >= 400 ? "amber" : status >= 300 ? "blue" : "green";
        return "<span class=\"status " + tone + "\">" + status + "</span>";
    }

    private static String when(ViewContext ctx, Entry e) {
        Duration age = Duration.between(e.createdAt(), ctx.now());
        long seconds = Math.max(0, age.getSeconds());
        String relative;
        if (seconds < 5) {
            relative = "just now";
        } else if (seconds < 60) {
            relative = seconds + "s ago";
        } else if (seconds < 3600) {
            relative = (seconds / 60) + "m ago";
        } else {
            relative = TIME.format(e.createdAt().atZone(ctx.zone()));
        }
        return "<time datetime=\"" + esc(e.createdAt()) + "\" title=\""
               + esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))) + "\">" + esc(relative) + "</time>";
    }

    private static String shortLocation(String location) {
        // com.acme.orders.OrderService.place(OrderService.java:42) → OrderService.place:42
        int paren = location.indexOf('(');
        if (paren < 0) {
            return location;
        }
        String method = location.substring(0, paren);
        int lastDot = method.lastIndexOf('.');
        int classDot = lastDot > 0 ? method.lastIndexOf('.', lastDot - 1) : -1;
        String shortMethod = classDot >= 0 ? method.substring(classDot + 1) : method;
        int colon = location.lastIndexOf(':');
        String line = colon > paren ? location.substring(colon + 1, location.length() - 1) : "";
        return line.isEmpty() ? shortMethod : shortMethod + ":" + line;
    }

    private static String title(Entry e) {
        return switch (e.type()) {
            case REQUEST -> e.getString(Entry.Content.METHOD, "") + " " + e.getString(Entry.Content.URI, "");
            case QUERY -> "Query #" + e.id();
            case EXCEPTION -> simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, "Exception"));
        };
    }

    private static String headerCells(EntryType type) {
        return switch (type) {
            case REQUEST -> "<th>Method</th><th>Path</th><th>Status</th><th class=\"num\">Duration</th>"
                            + "<th class=\"num\">Queries</th><th>When</th>";
            case QUERY -> "<th>Query</th><th class=\"num\">Duration</th><th>When</th>";
            case EXCEPTION -> "<th>Exception</th><th>Location</th><th>Handled</th><th>When</th>";
        };
    }

    private static int columnCount(EntryType type) {
        return switch (type) {
            case REQUEST -> 6;
            case QUERY -> 3;
            case EXCEPTION -> 4;
        };
    }

    private static String searchPlaceholder(EntryType type) {
        return switch (type) {
            case REQUEST -> "Filter by method or path…";
            case QUERY -> "Filter by SQL…";
            case EXCEPTION -> "Filter by class or message…";
        };
    }

    private static String[][] tagOptions(EntryType type) {
        return switch (type) {
            case REQUEST -> new String[][] {
                    {Entry.Tags.SLOW, "Slow"}, {Entry.Tags.FAILED, "Server errors (5xx)"},
                    {Entry.Tags.N_PLUS_ONE, "N+1 queries"}, {Entry.Tags.HAS_EXCEPTION, "With exception"}};
            case QUERY -> new String[][] {{Entry.Tags.SLOW, "Slow"}, {Entry.Tags.FAILED, "Failed"}};
            case EXCEPTION -> new String[][] {{"unhandled", "Unhandled"}, {"handled", "Handled"}};
        };
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
