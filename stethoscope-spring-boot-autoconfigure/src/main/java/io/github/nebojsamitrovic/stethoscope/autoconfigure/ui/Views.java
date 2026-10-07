package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.esc;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.simpleClassName;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.truncate;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
        StringBuilder nav = new StringBuilder();
        for (EntryType type : EntryType.values()) {
            nav.append("<a class=\"nav-item").append(type == active ? " active" : "").append("\" href=\"")
                    .append(esc(ctx.link("/" + ViewContext.section(type)))).append("\"")
                    .append(type == active ? " aria-current=\"page\"" : "").append(">")
                    .append("<span>").append(esc(type.label())).append("</span>")
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
                  <div class="actions">%s<button class="btn danger" hx-post="%s" hx-confirm="Clear all recorded entries?">Clear</button></div>
                </header>
                <div class="shell">
                <nav class="sidebar" aria-label="Entry types">%s</nav>
                <main>
                %s
                </main>
                </div>
                </body>
                </html>
                """.formatted(
                esc(title),
                esc(ctx.link("/assets/stethoscope.css")),
                esc(ctx.link("/assets/htmx.min.js")),
                hxHeaders,
                esc(ctx.link("/requests")), LOGO,
                recording,
                esc(ctx.link("/clear")),
                nav,
                body);
    }

    // ---------------------------------------------------------------- list pages

    static String listPage(ViewContext ctx, EntryType type, List<Entry> entries, String search, String tag) {
        String section = ViewContext.section(type);
        String rowsUrl = ctx.link("/" + section + "/rows");

        String[][] tagOptions = tagOptions(type);
        String select = "";
        if (tagOptions.length > 0) {
            StringBuilder options = new StringBuilder("<option value=\"\">All</option>");
            for (String[] option : tagOptions) {
                options.append("<option value=\"").append(esc(option[0])).append("\"")
                        .append(option[0].equals(tag) ? " selected" : "").append(">")
                        .append(esc(option[1])).append("</option>");
            }
            select = "<select name=\"tag\" aria-label=\"Filter\">" + options + "</select>";
        }

        String body = """
                <div class="toolbar">
                  <h1 class="page-title">%s</h1>
                  <form id="filters" class="filters" action="%s" method="get"
                        hx-get="%s" hx-target="#rows" hx-trigger="input changed delay:300ms, change">
                    <input type="search" name="q" value="%s" placeholder="%s" aria-label="Search" autocomplete="off">
                    %s
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
                esc(type.label()),
                esc(ctx.link("/" + section)),
                esc(rowsUrl),
                esc(search),
                esc(searchPlaceholder(type)),
                select,
                esc(section),
                headerCells(type),
                esc(rowsUrl),
                rows(ctx, type, entries));
        return page(ctx, type, type.label(), body);
    }

    static String rows(ViewContext ctx, EntryType type, List<Entry> entries) {
        if (entries.isEmpty()) {
            return "<tr class=\"empty\"><td colspan=\"" + columnCount(type) + "\">" + esc(emptyText(type)) + "</td></tr>";
        }
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(switch (type) {
                case REQUEST -> requestRow(ctx, entry);
                case QUERY -> queryRow(ctx, entry);
                case EXCEPTION -> exceptionRow(ctx, entry);
                case LOG -> logRow(ctx, entry);
                case HTTP_CLIENT -> httpClientRow(ctx, entry);
                case SCHEDULED -> scheduledRow(ctx, entry);
                case EVENT -> eventRow(ctx, entry);
                case CACHE -> cacheRow(ctx, entry);
                case MAIL -> mailRow(ctx, entry);
                case DUMP -> dumpRow(ctx, entry);
                case JOB -> jobRow(ctx, entry);
                case MODEL -> modelRow(ctx, entry);
                case SECURITY -> securityRow(ctx, entry);
                case MESSAGE -> messageRow(ctx, entry);
                case REDIS -> redisRow(ctx, entry);
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
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + esc(uri) + (query == null ? "" : "<span class=\"muted\">?" + esc(truncate(query, 60)) + "</span>")
               + "</a>" + (e.hasTag(Entry.Tags.HAS_EXCEPTION) ? " <span class=\"badge red\">exception</span>" : "")
               + "</td>"
               + "<td>" + statusBadge(status) + "</td>"
               + durationCell(e)
               + "<td class=\"num\">" + queries
               + (e.hasTag(Entry.Tags.N_PLUS_ONE) ? " <span class=\"badge amber\">N+1</span>" : "") + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String queryRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(truncate(e.getString(Entry.Content.SQL, ""), 160)) + "</a>"
               + (e.hasTag(Entry.Tags.FAILED) ? " <span class=\"badge red\">failed</span>" : "")
               + "</td>"
               + "<td class=\"num" + (e.hasTag(Entry.Tags.SLOW) ? " warn" : "") + "\">" + e.getLong(Entry.Content.DURATION_MS, 0) + " ms"
               + (e.hasTag(Entry.Tags.SLOW) ? " <span class=\"badge amber\">slow</span>" : "") + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String exceptionRow(ViewContext ctx, Entry e) {
        boolean handled = Boolean.TRUE.equals(e.get(Entry.Content.HANDLED));
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + "<strong>" + esc(simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, ""))) + "</strong>"
               + " <span class=\"muted\">" + esc(truncate(e.getString(Entry.Content.MESSAGE, ""), 120)) + "</span></a></td>"
               + "<td class=\"code small\">" + esc(shortLocation(e.getString(Entry.Content.LOCATION, ""))) + "</td>"
               + "<td>" + (handled ? "<span class=\"badge\">handled</span>" : "<span class=\"badge red\">unhandled</span>") + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String logRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td>" + levelBadge(e.getString(Entry.Content.LEVEL, "")) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + esc(truncate(e.getString(Entry.Content.MESSAGE, ""), 200)) + "</a>"
               + (e.hasTag(Entry.Tags.HAS_EXCEPTION)
                       ? " <span class=\"badge red\">" + esc(simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, "exception"))) + "</span>"
                       : "")
               + "</td>"
               + "<td class=\"code small muted\">" + esc(shortLogger(e.getString(Entry.Content.LOGGER, ""))) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String httpClientRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td>" + methodBadge(e.getString(Entry.Content.METHOD, "")) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(truncate(e.getString(Entry.Content.URL, ""), 160)) + "</a></td>"
               + "<td>" + httpStatus(e) + "</td>"
               + durationCell(e)
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String scheduledRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(shortTask(e.getString(Entry.Content.TASK, ""))) + "</a>"
               + (e.hasTag(Entry.Tags.N_PLUS_ONE) ? " <span class=\"badge amber\">N+1</span>" : "") + "</td>"
               + "<td>" + successBadge(e) + "</td>"
               + durationCell(e)
               + "<td class=\"num\">" + e.getLong(Entry.Content.QUERY_COUNT, 0) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String eventRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + "<strong>" + esc(simpleClassName(e.getString(Entry.Content.EVENT_CLASS, ""))) + "</strong>"
               + " <span class=\"muted code\">" + esc(truncate(e.getString(Entry.Content.PAYLOAD, ""), 140)) + "</span></a></td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String cacheRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td>" + cacheBadge(e.getString(Entry.Content.OPERATION, "")) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(truncate(e.getString(Entry.Content.KEY, "(all)"), 160)) + "</a></td>"
               + "<td>" + esc(e.getString(Entry.Content.CACHE_NAME, "")) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String mailRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + esc(truncate(e.getString(Entry.Content.SUBJECT, "(no subject)"), 140)) + "</a>"
               + (e.hasTag(Entry.Tags.FAILED) ? " <span class=\"badge red\">failed</span>" : "") + "</td>"
               + "<td class=\"small\">" + esc(truncate(joined(e.get(Entry.Content.TO)), 80)) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String dumpRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(truncate(joined(e.get(Entry.Content.VALUES)), 160)) + "</a></td>"
               + "<td class=\"code small\">" + esc(shortLocation(e.getString(Entry.Content.LOCATION, ""))) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String jobRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(shortTask(e.getString(Entry.Content.TASK, ""))) + "</a>"
               + (e.hasTag(Entry.Tags.N_PLUS_ONE) ? " <span class=\"badge amber\">N+1</span>" : "") + "</td>"
               + "<td>" + successBadge(e) + "</td>"
               + "<td class=\"num muted\">" + e.getLong(Entry.Content.WAIT_MS, 0) + " ms</td>"
               + durationCell(e)
               + "<td class=\"num\">" + e.getLong(Entry.Content.QUERY_COUNT, 0) + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String modelRow(ViewContext ctx, Entry e) {
        Map<String, String> changes = e.get(Entry.Content.CHANGES);
        return "<tr>"
               + "<td>" + actionBadge(e.getString(Entry.Content.ACTION, "")) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(simpleClassName(e.getString(Entry.Content.ENTITY, ""))) + "<span class=\"muted\">#"
               + esc(e.getString(Entry.Content.ENTITY_ID, "")) + "</span></a>"
               + (changes == null || changes.isEmpty() ? ""
                       : " <span class=\"muted small\">" + esc(truncate(String.join(", ", changes.keySet()), 100)) + "</span>")
               + "</td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String securityRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td>" + resultBadge(e.getString(Entry.Content.RESULT, "")) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "")
               + "<strong>" + esc(e.getString(Entry.Content.PRINCIPAL, "")) + "</strong> <span class=\"muted code\">"
               + esc(truncate(e.getString(Entry.Content.RESOURCE, e.getString(Entry.Content.KIND, "")), 140)) + "</span></a></td>"
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String messageRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td>" + directionBadge(e) + "</td>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + esc(e.getString(Entry.Content.DESTINATION, "")) + "</a> <span class=\"muted small\">"
               + esc(e.getString(Entry.Content.SYSTEM, "")) + "</span></td>"
               + "<td>" + successBadge(e) + "</td>"
               + durationCell(e)
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    private static String redisRow(ViewContext ctx, Entry e) {
        return "<tr>"
               + "<td class=\"main\">" + entryLink(ctx, e, "code")
               + "<strong>" + esc(e.getString(Entry.Content.COMMAND, "")) + "</strong> "
               + esc(truncate(e.getString(Entry.Content.ARGS, ""), 160)) + "</a>"
               + (e.hasTag(Entry.Tags.FAILED) ? " <span class=\"badge red\">failed</span>" : "") + "</td>"
               + durationCell(e)
               + whenCell(ctx, e)
               + "</tr>\n";
    }

    // ---------------------------------------------------------------- detail pages

    /**
     * @param batch    entries of the entry's own batch
     * @param children jobs dispatched from that batch
     * @param parent   entries of the batch that dispatched this entry (jobs only), otherwise empty
     */
    static String detail(ViewContext ctx, Entry entry, List<Entry> batch, List<Entry> children, List<Entry> parent) {
        String body = switch (entry.type()) {
            case REQUEST -> requestDetail(ctx, entry, batch, children);
            case QUERY -> queryDetail(ctx, entry, batch);
            case EXCEPTION -> exceptionDetail(ctx, entry, batch);
            case LOG -> logDetail(ctx, entry, batch);
            case HTTP_CLIENT -> httpClientDetail(ctx, entry, batch);
            case SCHEDULED -> scheduledDetail(ctx, entry, batch, children);
            case EVENT -> eventDetail(ctx, entry, batch);
            case CACHE -> cacheDetail(ctx, entry, batch);
            case MAIL -> mailDetail(ctx, entry, batch);
            case DUMP -> dumpDetail(ctx, entry, batch);
            case JOB -> jobDetail(ctx, entry, batch, children, parent);
            case MODEL -> modelDetail(ctx, entry, batch);
            case SECURITY -> securityDetail(ctx, entry, batch);
            case MESSAGE -> messageDetail(ctx, entry, batch, children);
            case REDIS -> redisDetail(ctx, entry, batch);
        };
        return page(ctx, entry.type(), title(entry), body);
    }

    private static String requestDetail(ViewContext ctx, Entry e, List<Entry> batch, List<Entry> children) {
        int status = (int) e.getLong(Entry.Content.STATUS, 0);
        String query = e.getString(Entry.Content.QUERY_STRING, null);

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

        out.append(duplicateQueries(e));
        out.append(related(ctx, e, batch, children));

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
                originLabel(batch), originLink(ctx, batch)));
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
                originLabel(batch), originLink(ctx, batch)));
        out.append(bodySection("Stack trace", e.getString(Entry.Content.STACK_TRACE, "")));
        return out.toString();
    }

    private static String logDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.LOG));
        out.append("<div class=\"detail-head\">").append(levelBadge(e.getString(Entry.Content.LEVEL, "")))
                .append("<h1 class=\"code\">").append(esc(shortLogger(e.getString(Entry.Content.LOGGER, "")))).append("</h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Logger", "<span class=\"code\">" + esc(e.getString(Entry.Content.LOGGER, "")) + "</span>",
                "Thread", esc(e.getString(Entry.Content.THREAD, "—")),
                originLabel(batch), originLink(ctx, batch)));
        out.append(bodySection("Message", e.getString(Entry.Content.MESSAGE, "")));
        out.append(keyValueSection("MDC", e.get(Entry.Content.MDC)));
        out.append(bodySection("Stack trace", e.getString(Entry.Content.STACK_TRACE, null)));
        return out.toString();
    }

    private static String httpClientDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.HTTP_CLIENT));
        out.append("<div class=\"detail-head\">").append(methodBadge(e.getString(Entry.Content.METHOD, "")))
                .append("<h1 class=\"code\">").append(esc(e.getString(Entry.Content.URL, ""))).append("</h1>")
                .append(httpStatus(e)).append("</div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                originLabel(batch), originLink(ctx, batch)));
        out.append(errorCallout(e));
        out.append(keyValueSection("Request headers", e.get(Entry.Content.REQUEST_HEADERS)));
        out.append(bodySection("Request body", e.getString(Entry.Content.REQUEST_BODY, null)));
        out.append(keyValueSection("Response headers", e.get(Entry.Content.RESPONSE_HEADERS)));
        out.append(bodySection("Response body", e.getString(Entry.Content.RESPONSE_BODY, null)));
        return out.toString();
    }

    private static String scheduledDetail(ViewContext ctx, Entry e, List<Entry> batch, List<Entry> children) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.SCHEDULED));
        out.append("<div class=\"detail-head\"><h1 class=\"code\">").append(esc(shortTask(e.getString(Entry.Content.TASK, ""))))
                .append("</h1>").append(successBadge(e)).append("</div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Task", "<span class=\"code\">" + esc(e.getString(Entry.Content.TASK, "")) + "</span>",
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                "Thread", esc(e.getString(Entry.Content.THREAD, "—")),
                "Queries", e.getLong(Entry.Content.QUERY_COUNT, 0) + " · " + e.getLong(Entry.Content.QUERY_TIME_MS, 0) + " ms"));
        out.append(errorCallout(e));
        out.append(duplicateQueries(e));
        out.append(related(ctx, e, batch, children));
        return out.toString();
    }

    private static String jobDetail(ViewContext ctx, Entry e, List<Entry> batch, List<Entry> children, List<Entry> parent) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.JOB));
        out.append("<div class=\"detail-head\"><h1 class=\"code\">").append(esc(shortTask(e.getString(Entry.Content.TASK, ""))))
                .append("</h1>").append(successBadge(e)).append("</div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Task", "<span class=\"code\">" + esc(e.getString(Entry.Content.TASK, "")) + "</span>",
                "Executor", esc(e.getString(Entry.Content.EXECUTOR, "—")),
                "Thread", esc(e.getString(Entry.Content.THREAD, "—")),
                "Waited", e.getLong(Entry.Content.WAIT_MS, 0) + " ms",
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                "Queries", e.getLong(Entry.Content.QUERY_COUNT, 0) + " · " + e.getLong(Entry.Content.QUERY_TIME_MS, 0) + " ms",
                "Dispatched by", originLink(ctx, parent)));
        out.append(errorCallout(e));
        out.append(duplicateQueries(e));
        out.append(related(ctx, e, batch, children));
        return out.toString();
    }

    private static String modelDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.MODEL));
        out.append("<div class=\"detail-head\">").append(actionBadge(e.getString(Entry.Content.ACTION, "")))
                .append("<h1 class=\"code\">").append(esc(simpleClassName(e.getString(Entry.Content.ENTITY, ""))))
                .append("<span class=\"muted\">#").append(esc(e.getString(Entry.Content.ENTITY_ID, ""))).append("</span></h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Entity", "<span class=\"code\">" + esc(e.getString(Entry.Content.ENTITY, "")) + "</span>",
                "Id", esc(e.getString(Entry.Content.ENTITY_ID, "")),
                originLabel(batch), originLink(ctx, batch)));
        String title = "updated".equals(e.getString(Entry.Content.ACTION, "")) ? "Changes" : "Attributes";
        out.append(keyValueSection(title, e.get(Entry.Content.CHANGES)));
        return out.toString();
    }

    private static String securityDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.SECURITY));
        out.append("<div class=\"detail-head\">").append(resultBadge(e.getString(Entry.Content.RESULT, "")))
                .append("<h1>").append(esc(e.getString(Entry.Content.PRINCIPAL, ""))).append("</h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Kind", esc(e.getString(Entry.Content.KIND, "")),
                "Resource", "<span class=\"code\">" + esc(e.getString(Entry.Content.RESOURCE, "—")) + "</span>",
                "Authorities", esc(joined(e.get(Entry.Content.AUTHORITIES))),
                originLabel(batch), originLink(ctx, batch)));
        out.append(errorCallout(e));
        out.append(bodySection("Decision", e.getString(Entry.Content.DETAILS, null)));
        return out.toString();
    }

    private static String messageDetail(ViewContext ctx, Entry e, List<Entry> batch, List<Entry> children) {
        boolean received = "received".equals(e.getString(Entry.Content.DIRECTION, ""));
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.MESSAGE));
        out.append("<div class=\"detail-head\">").append(directionBadge(e))
                .append("<h1 class=\"code\">").append(esc(e.getString(Entry.Content.DESTINATION, ""))).append("</h1>")
                .append(successBadge(e)).append("</div>");
        List<String> pairs = new ArrayList<>(List.of(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "System", esc(e.getString(Entry.Content.SYSTEM, "")),
                "Key", "<span class=\"code\">" + esc(e.getString(Entry.Content.KEY, "—")) + "</span>",
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms"));
        if (received) {
            pairs.addAll(List.of("Listener", esc(e.getString(Entry.Content.LISTENER, "—")),
                    "Queries", e.getLong(Entry.Content.QUERY_COUNT, 0) + " · " + e.getLong(Entry.Content.QUERY_TIME_MS, 0) + " ms"));
        } else {
            pairs.addAll(List.of(originLabel(batch), originLink(ctx, batch)));
        }
        out.append(facts(pairs.toArray(String[]::new)));
        out.append(errorCallout(e));
        out.append(bodySection("Payload", e.getString(Entry.Content.PAYLOAD, null)));
        out.append(keyValueSection("Metadata", e.get(Entry.Content.METADATA)));
        if (received) {
            out.append(duplicateQueries(e));
            out.append(related(ctx, e, batch, children));
        }
        return out.toString();
    }

    private static String redisDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.REDIS));
        out.append("<div class=\"detail-head\"><h1 class=\"code\">").append(esc(e.getString(Entry.Content.COMMAND, "")))
                .append("</h1>").append(e.hasTag(Entry.Tags.FAILED) ? "<span class=\"badge red\">failed</span>" : "").append("</div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Duration", e.getLong(Entry.Content.DURATION_MS, 0) + " ms",
                originLabel(batch), originLink(ctx, batch)));
        out.append(errorCallout(e));
        out.append(bodySection("Arguments", e.getString(Entry.Content.ARGS, null)));
        return out.toString();
    }

    private static String eventDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.EVENT));
        out.append("<div class=\"detail-head\"><h1>").append(esc(simpleClassName(e.getString(Entry.Content.EVENT_CLASS, ""))))
                .append("</h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Class", "<span class=\"code\">" + esc(e.getString(Entry.Content.EVENT_CLASS, "")) + "</span>",
                "Source", "<span class=\"code\">" + esc(e.getString(Entry.Content.SOURCE, "—")) + "</span>",
                originLabel(batch), originLink(ctx, batch)));
        out.append(bodySection("Payload", e.getString(Entry.Content.PAYLOAD, "")));
        return out.toString();
    }

    private static String cacheDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.CACHE));
        out.append("<div class=\"detail-head\">").append(cacheBadge(e.getString(Entry.Content.OPERATION, "")))
                .append("<h1>").append(esc(e.getString(Entry.Content.CACHE_NAME, ""))).append("</h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Cache", esc(e.getString(Entry.Content.CACHE_NAME, "")),
                "Operation", esc(e.getString(Entry.Content.OPERATION, "")),
                originLabel(batch), originLink(ctx, batch)));
        out.append(bodySection("Key", e.getString(Entry.Content.KEY, null)));
        out.append(bodySection("Value", e.getString(Entry.Content.VALUE, null)));
        return out.toString();
    }

    private static String mailDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.MAIL));
        out.append("<div class=\"detail-head\"><h1>").append(esc(e.getString(Entry.Content.SUBJECT, "(no subject)"))).append("</h1>")
                .append(e.hasTag(Entry.Tags.FAILED) ? "<span class=\"badge red\">failed</span>" : "<span class=\"badge green\">sent</span>")
                .append("</div>");
        List<String> pairs = new ArrayList<>(List.of(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "From", esc(joined(e.get(Entry.Content.FROM))),
                "To", esc(joined(e.get(Entry.Content.TO)))));
        for (String[] optional : new String[][] {{"Cc", Entry.Content.CC}, {"Bcc", Entry.Content.BCC}}) {
            Collection<?> values = e.get(optional[1]);
            if (values != null && !values.isEmpty()) {
                pairs.add(optional[0]);
                pairs.add(esc(joined(values)));
            }
        }
        pairs.add(originLabel(batch));
        pairs.add(originLink(ctx, batch));
        out.append(facts(pairs.toArray(String[]::new)));
        out.append(errorCallout(e));

        List<String> attachments = e.get(Entry.Content.ATTACHMENTS);
        if (attachments != null && !attachments.isEmpty()) {
            out.append("<section><h2>Attachments</h2><ul class=\"links\">");
            for (String attachment : attachments) {
                out.append("<li class=\"code\">").append(esc(attachment)).append("</li>");
            }
            out.append("</ul></section>");
        }
        String html = e.getString(Entry.Content.HTML_BODY, null);
        if (html != null) {
            // sandbox without allow-scripts / allow-same-origin: the mail cannot run code or reach the dashboard
            out.append("<section><h2>Preview</h2><iframe class=\"mail-preview\" sandbox title=\"Mail preview\" srcdoc=\"")
                    .append(esc(html)).append("\"></iframe></section>");
        }
        out.append(bodySection("Text", e.getString(Entry.Content.TEXT_BODY, null)));
        out.append(bodySection("HTML source", html));
        return out.toString();
    }

    private static String dumpDetail(ViewContext ctx, Entry e, List<Entry> batch) {
        StringBuilder out = new StringBuilder();
        out.append(backLink(ctx, EntryType.DUMP));
        out.append("<div class=\"detail-head\"><h1>Dump</h1></div>");
        out.append(facts(
                "Time", esc(DATE_TIME.format(e.createdAt().atZone(ctx.zone()))),
                "Location", "<span class=\"code\">" + esc(e.getString(Entry.Content.LOCATION, "—")) + "</span>",
                originLabel(batch), originLink(ctx, batch)));
        List<String> values = e.get(Entry.Content.VALUES);
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                out.append(bodySection(values.size() == 1 ? "Value" : "Value " + (i + 1), values.get(i)));
            }
        }
        return out.toString();
    }

    static String notFound(ViewContext ctx) {
        return page(ctx, null, "Not found", backLink(ctx, EntryType.REQUEST)
                + "<div class=\"callout\"><strong>Entry not found.</strong> It may have been evicted "
                + "(only the newest entries are kept) or cleared.</div>");
    }

    // ---------------------------------------------------------------- pieces

    /** Everything else recorded in the same batch, grouped by type, as Telescope shows under a request. */
    private static String related(ViewContext ctx, Entry self, List<Entry> batch, List<Entry> children) {
        StringBuilder out = new StringBuilder();
        for (EntryType type : EntryType.values()) {
            if (type == EntryType.REQUEST || type == EntryType.SCHEDULED) {
                continue;
            }
            List<Entry> ofType = type == EntryType.JOB
                    ? children
                    : batch.stream().filter(b -> b.type() == type && b.id() != self.id() && !isOrigin(b)).toList();
            if (ofType.isEmpty() && type != EntryType.QUERY) {
                continue;
            }
            out.append("<section><h2>").append(esc(type.label())).append(" <span class=\"count\">")
                    .append(ofType.size()).append("</span></h2>");
            if (ofType.isEmpty()) {
                out.append("<p class=\"muted\">No queries were recorded.</p></section>");
                continue;
            }
            out.append("<div class=\"table-wrap\"><table class=\"entries\"><tbody>");
            for (Entry entry : ofType) {
                out.append("<tr><td class=\"main\">").append(entryLink(ctx, entry, summaryIsCode(type) ? "code" : ""))
                        .append(summary(entry)).append("</a></td>");
                if (entry.content().containsKey(Entry.Content.DURATION_MS)) {
                    out.append(durationCell(entry));
                } else {
                    out.append("<td></td>");
                }
                out.append("</tr>");
            }
            out.append("</tbody></table></div></section>");
        }
        return out.toString();
    }

    /** One-line, already-escaped description of an entry. */
    private static String summary(Entry e) {
        return switch (e.type()) {
            case REQUEST -> esc(e.getString(Entry.Content.METHOD, "") + " " + e.getString(Entry.Content.URI, ""));
            case QUERY -> esc(truncate(e.getString(Entry.Content.SQL, ""), 200));
            case EXCEPTION -> "<strong>" + esc(simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, ""))) + "</strong> <span class=\"muted\">"
                              + esc(truncate(e.getString(Entry.Content.MESSAGE, ""), 160)) + "</span>";
            case LOG -> levelBadge(e.getString(Entry.Content.LEVEL, "")) + " " + esc(truncate(e.getString(Entry.Content.MESSAGE, ""), 180));
            case HTTP_CLIENT -> methodBadge(e.getString(Entry.Content.METHOD, "")) + " " + esc(truncate(e.getString(Entry.Content.URL, ""), 160))
                                + " " + httpStatus(e);
            case SCHEDULED -> esc(shortTask(e.getString(Entry.Content.TASK, "")));
            case EVENT -> "<strong>" + esc(simpleClassName(e.getString(Entry.Content.EVENT_CLASS, ""))) + "</strong>";
            case CACHE -> cacheBadge(e.getString(Entry.Content.OPERATION, "")) + " " + esc(e.getString(Entry.Content.CACHE_NAME, ""))
                          + " <span class=\"muted\">" + esc(truncate(e.getString(Entry.Content.KEY, ""), 120)) + "</span>";
            case MAIL -> esc(e.getString(Entry.Content.SUBJECT, "(no subject)")) + " <span class=\"muted\">→ "
                         + esc(truncate(joined(e.get(Entry.Content.TO)), 80)) + "</span>";
            case DUMP -> esc(truncate(joined(e.get(Entry.Content.VALUES)), 180));
            case JOB -> esc(shortTask(e.getString(Entry.Content.TASK, ""))) + " " + successBadge(e);
            case MODEL -> actionBadge(e.getString(Entry.Content.ACTION, "")) + " " + esc(simpleClassName(e.getString(Entry.Content.ENTITY, "")))
                          + "<span class=\"muted\">#" + esc(e.getString(Entry.Content.ENTITY_ID, "")) + "</span>";
            case SECURITY -> resultBadge(e.getString(Entry.Content.RESULT, "")) + " " + esc(e.getString(Entry.Content.PRINCIPAL, ""))
                             + " <span class=\"muted\">" + esc(truncate(e.getString(Entry.Content.RESOURCE, ""), 120)) + "</span>";
            case MESSAGE -> directionBadge(e) + " " + esc(e.getString(Entry.Content.DESTINATION, ""))
                            + " <span class=\"muted\">" + esc(e.getString(Entry.Content.SYSTEM, "")) + "</span>";
            case REDIS -> "<strong>" + esc(e.getString(Entry.Content.COMMAND, "")) + "</strong> " + esc(truncate(e.getString(Entry.Content.ARGS, ""), 160));
        };
    }

    private static boolean summaryIsCode(EntryType type) {
        return type == EntryType.QUERY || type == EntryType.DUMP || type == EntryType.REDIS;
    }

    private static String duplicateQueries(Entry e) {
        Map<String, Integer> duplicates = e.get(Entry.Content.DUPLICATE_QUERIES);
        if (duplicates == null || duplicates.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<div class=\"callout amber\"><strong>Possible N+1.</strong> These statements ran repeatedly. "
                                              + "Consider a join fetch, an entity graph or batch loading.<ul>");
        duplicates.forEach((sql, count) -> out.append("<li><span class=\"times\">× ").append(count)
                .append("</span><code>").append(esc(truncate(sql, 300))).append("</code></li>"));
        return out.append("</ul></div>").toString();
    }

    private static String errorCallout(Entry e) {
        String error = e.getString(Entry.Content.ERROR, null);
        return error == null ? "" : "<div class=\"callout red\"><strong>Failed.</strong> <span class=\"code\">" + esc(error) + "</span></div>";
    }

    private static String backLink(ViewContext ctx, EntryType type) {
        return "<a class=\"back\" href=\"" + esc(ctx.link("/" + ViewContext.section(type))) + "\">← "
               + esc(type.label()) + "</a>";
    }

    private static String entryLink(ViewContext ctx, Entry e, String cssClass) {
        return "<a class=\"row-link" + (cssClass.isEmpty() ? "" : " " + cssClass) + "\" href=\""
               + esc(ctx.link("/entries/" + e.id())) + "\">";
    }

    /** Entries that open a batch: a request, a scheduled run, a job or a received message. */
    private static boolean isOrigin(Entry e) {
        return switch (e.type()) {
            case REQUEST, SCHEDULED, JOB -> true;
            case MESSAGE -> "received".equals(e.getString(Entry.Content.DIRECTION, ""));
            default -> false;
        };
    }

    /** The unit of work an entry belongs to. */
    private static Optional<Entry> origin(List<Entry> batch) {
        return batch.stream().filter(Views::isOrigin).findFirst();
    }

    private static String originLabel(List<Entry> batch) {
        return origin(batch).map(o -> switch (o.type()) {
            case SCHEDULED -> "Scheduled run";
            case JOB -> "Job";
            case MESSAGE -> "Message";
            default -> "Request";
        }).orElse("Request");
    }

    private static String originLink(ViewContext ctx, List<Entry> batch) {
        return origin(batch).map(o -> "<a href=\"" + esc(ctx.link("/entries/" + o.id())) + "\">"
                                      + switch (o.type()) {
                                          case SCHEDULED, JOB -> esc(shortTask(o.getString(Entry.Content.TASK, "")));
                                          case MESSAGE -> esc(o.getString(Entry.Content.DESTINATION, ""));
                                          default -> esc(o.getString(Entry.Content.METHOD, "")) + " " + esc(o.getString(Entry.Content.URI, ""));
                                      }
                                      + "</a>")
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

    private static String durationCell(Entry e) {
        return "<td class=\"num" + (e.hasTag(Entry.Tags.SLOW) ? " warn" : "") + "\">" + e.getLong(Entry.Content.DURATION_MS, 0) + " ms</td>";
    }

    private static String whenCell(ViewContext ctx, Entry e) {
        return "<td class=\"when\">" + when(ctx, e) + "</td>";
    }

    private static String methodBadge(String method) {
        return "<span class=\"method m-" + esc(method.toLowerCase(Locale.ROOT)) + "\">" + esc(method) + "</span>";
    }

    private static String statusBadge(int status) {
        String tone = status >= 500 ? "red" : status >= 400 ? "amber" : status >= 300 ? "blue" : "green";
        return "<span class=\"status " + tone + "\">" + status + "</span>";
    }

    private static String httpStatus(Entry e) {
        return e.content().containsKey(Entry.Content.STATUS)
                ? statusBadge((int) e.getLong(Entry.Content.STATUS, 0))
                : "<span class=\"status red\">error</span>";
    }

    private static String successBadge(Entry e) {
        return Boolean.FALSE.equals(e.get(Entry.Content.SUCCESS))
                ? "<span class=\"badge red\">failed</span>"
                : "<span class=\"badge green\">ok</span>";
    }

    private static String levelBadge(String level) {
        String tone = switch (level.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> " red";
            case "WARN" -> " amber";
            case "INFO" -> " blue";
            default -> "";
        };
        return "<span class=\"level" + tone + "\">" + esc(level) + "</span>";
    }

    private static String actionBadge(String action) {
        String tone = switch (action) {
            case "created" -> " green";
            case "updated" -> " blue";
            case "deleted" -> " red";
            default -> "";
        };
        return "<span class=\"level" + tone + "\">" + esc(action) + "</span>";
    }

    private static String resultBadge(String result) {
        String tone = switch (result) {
            case "granted", "authenticated" -> " green";
            case "denied", "failed" -> " red";
            default -> "";
        };
        return "<span class=\"level" + tone + "\">" + esc(result) + "</span>";
    }

    private static String directionBadge(Entry e) {
        String direction = e.getString(Entry.Content.DIRECTION, "");
        return "<span class=\"level" + ("received".equals(direction) ? " blue" : " green") + "\">" + esc(direction) + "</span>";
    }

    private static String cacheBadge(String operation) {
        String tone = switch (operation) {
            case "hit" -> " green";
            case "miss" -> " amber";
            case "evict", "clear" -> " red";
            default -> " blue";
        };
        return "<span class=\"level" + tone + "\">" + esc(operation) + "</span>";
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

    /** {@code com.acme.jobs.Cleanup#purge} → {@code Cleanup#purge}. */
    private static String shortTask(String task) {
        int hash = task.indexOf('#');
        String type = hash < 0 ? task : task.substring(0, hash);
        return simpleClassName(type) + (hash < 0 ? "" : task.substring(hash));
    }

    /** {@code org.springframework.web.servlet.DispatcherServlet} → {@code o.s.w.s.DispatcherServlet}. */
    static String shortLogger(String logger) {
        String[] parts = logger.split("\\.");
        if (parts.length <= 1) {
            return logger;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                out.append(parts[i].charAt(0)).append('.');
            }
        }
        return out.append(parts[parts.length - 1]).toString();
    }

    private static String joined(Object values) {
        if (values instanceof Collection<?> collection) {
            return String.join(", ", collection.stream().map(String::valueOf).toList());
        }
        return values == null ? "" : String.valueOf(values);
    }

    private static String title(Entry e) {
        return switch (e.type()) {
            case REQUEST -> e.getString(Entry.Content.METHOD, "") + " " + e.getString(Entry.Content.URI, "");
            case QUERY -> "Query #" + e.id();
            case EXCEPTION -> simpleClassName(e.getString(Entry.Content.EXCEPTION_CLASS, "Exception"));
            case LOG -> e.getString(Entry.Content.LEVEL, "Log") + " " + shortLogger(e.getString(Entry.Content.LOGGER, ""));
            case HTTP_CLIENT -> e.getString(Entry.Content.METHOD, "") + " " + e.getString(Entry.Content.URL, "");
            case SCHEDULED -> shortTask(e.getString(Entry.Content.TASK, "Scheduled task"));
            case EVENT -> simpleClassName(e.getString(Entry.Content.EVENT_CLASS, "Event"));
            case CACHE -> "Cache " + e.getString(Entry.Content.OPERATION, "");
            case MAIL -> e.getString(Entry.Content.SUBJECT, "Mail");
            case DUMP -> "Dump #" + e.id();
            case JOB -> shortTask(e.getString(Entry.Content.TASK, "Job"));
            case MODEL -> simpleClassName(e.getString(Entry.Content.ENTITY, "")) + "#" + e.getString(Entry.Content.ENTITY_ID, "");
            case SECURITY -> e.getString(Entry.Content.RESULT, "") + " " + e.getString(Entry.Content.PRINCIPAL, "");
            case MESSAGE -> e.getString(Entry.Content.DESTINATION, "Message");
            case REDIS -> e.getString(Entry.Content.COMMAND, "Redis");
        };
    }

    private static String headerCells(EntryType type) {
        return switch (type) {
            case REQUEST -> "<th>Method</th><th>Path</th><th>Status</th><th class=\"num\">Duration</th>"
                            + "<th class=\"num\">Queries</th><th>When</th>";
            case QUERY -> "<th>Query</th><th class=\"num\">Duration</th><th>When</th>";
            case EXCEPTION -> "<th>Exception</th><th>Location</th><th>Handled</th><th>When</th>";
            case LOG -> "<th>Level</th><th>Message</th><th>Logger</th><th>When</th>";
            case HTTP_CLIENT -> "<th>Method</th><th>URL</th><th>Status</th><th class=\"num\">Duration</th><th>When</th>";
            case SCHEDULED -> "<th>Task</th><th>Status</th><th class=\"num\">Duration</th><th class=\"num\">Queries</th><th>When</th>";
            case EVENT -> "<th>Event</th><th>When</th>";
            case CACHE -> "<th>Operation</th><th>Key</th><th>Cache</th><th>When</th>";
            case MAIL -> "<th>Subject</th><th>To</th><th>When</th>";
            case DUMP -> "<th>Value</th><th>Location</th><th>When</th>";
            case JOB -> "<th>Job</th><th>Status</th><th class=\"num\">Waited</th><th class=\"num\">Duration</th>"
                        + "<th class=\"num\">Queries</th><th>When</th>";
            case MODEL -> "<th>Action</th><th>Model</th><th>When</th>";
            case SECURITY -> "<th>Result</th><th>Principal</th><th>When</th>";
            case MESSAGE -> "<th>Direction</th><th>Destination</th><th>Status</th><th class=\"num\">Duration</th><th>When</th>";
            case REDIS -> "<th>Command</th><th class=\"num\">Duration</th><th>When</th>";
        };
    }

    private static int columnCount(EntryType type) {
        return switch (type) {
            case REQUEST, JOB -> 6;
            case HTTP_CLIENT, SCHEDULED, MESSAGE -> 5;
            case EXCEPTION, LOG, CACHE -> 4;
            case QUERY, MAIL, DUMP, MODEL, SECURITY, REDIS -> 3;
            case EVENT -> 2;
        };
    }

    private static String emptyText(EntryType type) {
        return switch (type) {
            case SCHEDULED -> "No scheduled runs yet. @Scheduled methods show up here once they run (requires @EnableScheduling).";
            case HTTP_CLIENT -> "No outgoing calls yet. Calls made with RestTemplate, RestClient or WebClient built "
                                + "from Spring Boot's builders show up here.";
            case DUMP -> "Nothing dumped yet. Call Stethoscope.dump(value) anywhere in your code.";
            case MAIL -> "No mail sent yet. Mail sent through a JavaMailSender bean shows up here.";
            case CACHE -> "No cache activity yet. Operations on Spring's CacheManager (e.g. @Cacheable) show up here.";
            case JOB -> "No jobs yet. @Async methods and tasks submitted to Spring TaskExecutor beans show up here.";
            case MODEL -> "No model changes yet. JPA entity inserts, updates and deletes (Hibernate) show up here.";
            case SECURITY -> "No security events yet. Logins, failed logins and denied access (Spring Security) show up here.";
            case MESSAGE -> "No messages yet. Kafka and RabbitMQ messages sent and received through Spring show up here.";
            case REDIS -> "No Redis commands yet. Commands sent through Spring Data Redis show up here.";
            default -> "Nothing here yet. Use your app and entries will appear as they happen.";
        };
    }

    private static String searchPlaceholder(EntryType type) {
        return switch (type) {
            case REQUEST -> "Filter by method or path…";
            case QUERY -> "Filter by SQL…";
            case EXCEPTION -> "Filter by class or message…";
            case LOG -> "Filter by logger or message…";
            case HTTP_CLIENT -> "Filter by method or URL…";
            case SCHEDULED -> "Filter by task…";
            case EVENT -> "Filter by event class or payload…";
            case CACHE -> "Filter by cache or key…";
            case MAIL -> "Filter by subject or recipient…";
            case DUMP -> "Filter by value…";
            case JOB -> "Filter by job…";
            case MODEL -> "Filter by model or id…";
            case SECURITY -> "Filter by principal or resource…";
            case MESSAGE -> "Filter by topic, queue or listener…";
            case REDIS -> "Filter by command or key…";
        };
    }

    private static String[][] tagOptions(EntryType type) {
        return switch (type) {
            case REQUEST -> new String[][] {
                    {Entry.Tags.SLOW, "Slow"}, {Entry.Tags.FAILED, "Server errors (5xx)"},
                    {Entry.Tags.N_PLUS_ONE, "N+1 queries"}, {Entry.Tags.HAS_EXCEPTION, "With exception"}};
            case QUERY -> new String[][] {{Entry.Tags.SLOW, "Slow"}, {Entry.Tags.FAILED, "Failed"}};
            case EXCEPTION -> new String[][] {{"unhandled", "Unhandled"}, {"handled", "Handled"}};
            case LOG -> new String[][] {
                    {"error", "Error"}, {"warn", "Warn"}, {"info", "Info"}, {"debug", "Debug"}, {"trace", "Trace"},
                    {Entry.Tags.HAS_EXCEPTION, "With exception"}};
            case HTTP_CLIENT -> new String[][] {{Entry.Tags.FAILED, "Failed"}, {Entry.Tags.SLOW, "Slow"}};
            case SCHEDULED -> new String[][] {
                    {Entry.Tags.FAILED, "Failed"}, {Entry.Tags.N_PLUS_ONE, "N+1 queries"}, {Entry.Tags.HAS_EXCEPTION, "With exception"}};
            case CACHE -> new String[][] {{"hit", "Hits"}, {"miss", "Misses"}, {"put", "Puts"}, {"evict", "Evictions"}, {"clear", "Clears"}};
            case MAIL -> new String[][] {{Entry.Tags.FAILED, "Failed"}};
            case JOB -> new String[][] {
                    {Entry.Tags.FAILED, "Failed"}, {Entry.Tags.N_PLUS_ONE, "N+1 queries"}, {Entry.Tags.HAS_EXCEPTION, "With exception"}};
            case MODEL -> new String[][] {{"created", "Created"}, {"updated", "Updated"}, {"deleted", "Deleted"}};
            case SECURITY -> new String[][] {
                    {"denied", "Denied"}, {"granted", "Granted"}, {"failed", "Failed logins"}, {"authenticated", "Logins"},
                    {"logout", "Logouts"}};
            case MESSAGE -> new String[][] {
                    {"received", "Received"}, {"sent", "Sent"}, {Entry.Tags.FAILED, "Failed"}, {"kafka", "Kafka"}, {"rabbitmq", "RabbitMQ"}};
            case REDIS -> new String[][] {{Entry.Tags.FAILED, "Failed"}};
            case EVENT, DUMP -> new String[0][];
        };
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
