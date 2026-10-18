package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.esc;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.simpleClassName;
import static io.github.nebojsamitrovic.stethoscope.autoconfigure.ui.Html.truncate;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricRow;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The overview: what the application did over a period, from the metrics in the database. First
 * page of the dashboard, rendered as plain strings like the rest of it.
 */
final class OverviewViews {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm");

    private OverviewViews() {
    }

    static String page(ViewContext ctx, Overview d) {
        StringBuilder tabs = new StringBuilder();
        for (Period period : Period.values()) {
            boolean active = period == d.period();
            tabs.append("<a href=\"").append(esc(ctx.link("/overview?period=" + period.slug()))).append("\"")
                    .append(active ? " class=\"active\" aria-current=\"page\"" : "").append(">")
                    .append(esc(period.slug())).append("</a>");
        }
        String body = """
                <div class="toolbar">
                  <h1 class="page-title">Overview</h1>
                  <nav class="period-tabs" aria-label="Period">%s</nav>
                  <label class="live"><input type="checkbox" id="live" checked> Live</label>
                </div>
                <div id="cards" hx-get="%s" hx-trigger="every 15s [document.getElementById('live').checked]">
                %s
                </div>
                """.formatted(
                tabs,
                esc(ctx.link("/overview/cards?period=" + d.period().slug())),
                cards(ctx, d));
        return Views.page(ctx, null, true, "Overview", body);
    }

    static String cards(ViewContext ctx, Overview d) {
        return "<div class=\"overview-grid\">"
               + throughputCard(ctx, d)
               + serversCard(d)
               + topCard("Slow requests", "≥ " + d.slowRequestMillis() + " ms", d.slowRequests(),
                       new String[] {"Route", "Count", "Avg", "Max"}, row -> routeCell(row)
                               + countCell(row) + msCell(row.average()) + msCell(row.peak()))
               + topCard("Slow queries", "≥ " + d.slowQueryMillis() + " ms", d.slowQueries(),
                       new String[] {"Query", "Count", "Avg", "Max"}, row -> codeCell(row.key())
                               + countCell(row) + msCell(row.average()) + msCell(row.peak()))
               + topCard("Exceptions", null, d.exceptions(),
                       new String[] {"Exception", "Count", "Last seen"}, row -> exceptionCell(row)
                               + countCell(row) + agoCell(d.now(), row.lastSeen()))
               + topCard("Jobs", "slowest first", d.jobs(),
                       new String[] {"Task", "Runs", "Avg", "Max"}, row -> taskCell(row.key(), null)
                               + countCell(row) + msCell(row.average()) + msCell(row.peak()))
               + topCard("Failed jobs", null, d.failedJobs(),
                       new String[] {"Task", "Failures", "Last seen"}, row -> taskCell(row.key(), row.sample())
                               + countCell(row) + agoCell(d.now(), row.lastSeen()))
               + topCard("Slow outgoing requests", "≥ " + d.slowOutgoingMillis() + " ms", d.slowOutgoing(),
                       new String[] {"Target", "Count", "Avg", "Max"}, row -> outgoingCell(row)
                               + countCell(row) + msCell(row.average()) + msCell(row.peak()))
               + "</div>";
    }

    // ---------------------------------------------------------------- throughput

    private static String throughputCard(ViewContext ctx, Overview d) {
        Period period = d.period();
        int bars = period.bars();
        long barSeconds = period.barSeconds();
        long[] ok = new long[bars];
        long[] client = new long[bars];
        long[] server = new long[bars];
        long[] peak = new long[bars];
        long requests = 0;
        long clientErrors = 0;
        long serverErrors = 0;
        long totalMs = 0;
        long peakMs = 0;
        for (MetricRow row : d.throughput()) {
            int i = (int) ((row.bucketStart() - d.from()) / barSeconds);
            if (i < 0 || i >= bars) {
                continue;
            }
            switch (row.key()) {
                case "5xx" -> {
                    server[i] += row.hits();
                    serverErrors += row.hits();
                }
                case "4xx" -> {
                    client[i] += row.hits();
                    clientErrors += row.hits();
                }
                default -> ok[i] += row.hits();
            }
            peak[i] = Math.max(peak[i], row.peak());
            requests += row.hits();
            totalMs += row.total();
            peakMs = Math.max(peakMs, row.peak());
        }

        long highest = 1;
        for (int i = 0; i < bars; i++) {
            highest = Math.max(highest, ok[i] + client[i] + server[i]);
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append(bars * 10).append(" 100\" preserveAspectRatio=\"none\" role=\"img\"")
                .append(" aria-label=\"Requests per ").append(esc(barLabel(barSeconds))).append("\">");
        for (int i = 0; i < bars; i++) {
            long sum = ok[i] + client[i] + server[i];
            Instant start = Instant.ofEpochSecond(d.from() + i * barSeconds);
            svg.append("<g><title>").append(esc(time(ctx, start, period))).append(" · ").append(sum).append(" requests");
            if (client[i] > 0) {
                svg.append(", ").append(client[i]).append(" × 4xx");
            }
            if (server[i] > 0) {
                svg.append(", ").append(server[i]).append(" × 5xx");
            }
            if (sum > 0) {
                svg.append(", max ").append(peak[i]).append(" ms");
            }
            svg.append("</title>");
            double y = 100;
            y = bar(svg, "ok", i, y, ok[i], highest);
            y = bar(svg, "client", i, y, client[i], highest);
            bar(svg, "server", i, y, server[i], highest);
            // full-height hit area so the tooltip works on empty and short bars too
            svg.append("<rect class=\"hit\" x=\"").append(i * 10).append("\" y=\"0\" width=\"10\" height=\"100\"/></g>");
        }
        svg.append("</svg>");

        String facts = "<dl class=\"facts\">"
                       + fact("Requests", String.valueOf(requests))
                       + fact("Per minute", perMinute(requests, d.now() - d.from()))
                       + fact("4xx", toned(clientErrors, "amber"))
                       + fact("5xx", toned(serverErrors, "red"))
                       + fact("Avg", requests == 0 ? "—" : (totalMs / requests) + " ms")
                       + fact("Max", requests == 0 ? "—" : peakMs + " ms")
                       + "</dl>";

        Instant first = Instant.ofEpochSecond(d.from());
        Instant last = Instant.ofEpochSecond(d.from() + (bars - 1) * barSeconds);
        return "<section class=\"card wide\"><header><h2>Requests</h2>"
               + "<div class=\"legend\"><span><i class=\"ok\"></i>2xx/3xx</span><span><i class=\"client\"></i>4xx</span>"
               + "<span><i class=\"server\"></i>5xx</span></div></header>"
               + facts
               + "<div class=\"chart\">" + svg
               + "<div class=\"axis\"><span>" + esc(time(ctx, first, period)) + "</span><span>"
               + esc(time(ctx, last, period)) + "</span></div></div>"
               + "</section>";
    }

    private static double bar(StringBuilder svg, String tone, int index, double bottom, long value, long highest) {
        if (value <= 0) {
            return bottom;
        }
        double height = Math.max(1.5, value * 96.0 / highest);
        double top = bottom - height;
        svg.append("<rect class=\"").append(tone).append("\" x=\"").append(index * 10 + 1).append("\" y=\"")
                .append(String.format(java.util.Locale.ROOT, "%.2f", top)).append("\" width=\"8\" height=\"")
                .append(String.format(java.util.Locale.ROOT, "%.2f", height)).append("\"/>");
        return top;
    }

    // ---------------------------------------------------------------- servers

    private static String serversCard(Overview d) {
        Map<String, MetricRow> latestCpu = latest(d.cpu());
        Map<String, MetricRow> latestHeap = latest(d.heap());
        Map<String, Long> peakCpu = peaks(d.cpu());
        Map<String, Long> peakHeap = peaks(d.heap());
        TreeMap<String, Boolean> instances = new TreeMap<>();
        latestCpu.keySet().forEach(k -> instances.put(k, true));
        latestHeap.keySet().forEach(k -> instances.put(k, true));

        StringBuilder rows = new StringBuilder();
        for (String instance : instances.keySet()) {
            MetricRow cpu = latestCpu.get(instance);
            MetricRow heap = latestHeap.get(instance);
            String cpuCell = cpu == null ? "<td class=\"num muted\">—</td>"
                    : "<td class=\"num\">" + meter(cpu.average(), 1000) + percent(cpu.average())
                      + " <span class=\"muted small\">max " + percent(peakCpu.getOrDefault(instance, 0L)) + "</span></td>";
            String heapCell;
            if (heap == null) {
                heapCell = "<td class=\"num muted\">—</td>";
            } else {
                long max = parseLong(heap.sample());
                heapCell = "<td class=\"num\">" + (max > 0 ? meter(heap.average(), max) : "") + heap.average() + " MB"
                           + (max > 0 ? " <span class=\"muted small\">of " + max + " MB</span>" : "")
                           + " <span class=\"muted small\">max " + peakHeap.getOrDefault(instance, 0L) + " MB</span></td>";
            }
            long seen = Math.max(cpu == null ? 0 : cpu.lastSeen(), heap == null ? 0 : heap.lastSeen());
            rows.append("<tr><td class=\"main\">").append(esc(instance)).append("</td>")
                    .append(cpuCell).append(heapCell).append(agoCell(d.now(), seen)).append("</tr>");
        }
        String body = rows.length() == 0
                ? "<p class=\"card-empty\">No samples yet. Each instance reports once a minute.</p>"
                : "<div class=\"table-scroll\"><table class=\"entries\"><thead><tr><th>Instance</th><th class=\"num\">CPU</th>"
                  + "<th class=\"num\">Heap</th><th class=\"num\">Reported</th></tr></thead><tbody>" + rows
                  + "</tbody></table></div>";
        return "<section class=\"card wide\"><header><h2>Servers</h2><span class=\"muted small\">process CPU and JVM heap</span>"
               + "</header>" + body + "</section>";
    }

    private static Map<String, MetricRow> latest(List<MetricRow> rows) {
        Map<String, MetricRow> latest = new TreeMap<>();
        for (MetricRow row : rows) {
            latest.merge(row.key(), row, (a, b) -> b.bucketStart() >= a.bucketStart() ? b : a);
        }
        return latest;
    }

    private static Map<String, Long> peaks(List<MetricRow> rows) {
        Map<String, Long> peaks = new TreeMap<>();
        for (MetricRow row : rows) {
            peaks.merge(row.key(), row.peak(), Math::max);
        }
        return peaks;
    }

    private static String meter(long value, long max) {
        long pct = Math.min(100, Math.max(0, value * 100 / Math.max(1, max)));
        return "<span class=\"meter" + (pct >= 85 ? " hot" : "") + "\" aria-hidden=\"true\"><span style=\"width:" + pct
               + "%\"></span></span>";
    }

    // ---------------------------------------------------------------- top lists

    private interface Cells {
        String render(MetricRow row);
    }

    private static String topCard(String title, String note, List<MetricRow> rows, String[] headers, Cells cells) {
        StringBuilder out = new StringBuilder("<section class=\"card\"><header><h2>").append(esc(title)).append("</h2>");
        if (note != null) {
            out.append("<span class=\"muted small\">").append(esc(note)).append("</span>");
        }
        out.append("</header>");
        if (rows.isEmpty()) {
            return out.append("<p class=\"card-empty\">Nothing in this period.</p></section>").toString();
        }
        out.append("<div class=\"table-scroll\"><table class=\"entries\"><thead><tr>");
        for (int i = 0; i < headers.length; i++) {
            out.append("<th").append(i == 0 ? "" : " class=\"num\"").append(">").append(esc(headers[i])).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (MetricRow row : rows) {
            out.append("<tr>").append(cells.render(row)).append("</tr>");
        }
        return out.append("</tbody></table></div></section>").toString();
    }

    private static String routeCell(MetricRow row) {
        String key = row.key();
        int space = key.indexOf(' ');
        String method = space < 0 ? "" : key.substring(0, space);
        String route = space < 0 ? key : key.substring(space + 1);
        return "<td class=\"main\" title=\"" + esc(row.sample() == null ? key : key + "\n" + row.sample()) + "\">"
               + (method.isEmpty() ? "" : "<span class=\"method m-" + esc(method.toLowerCase(java.util.Locale.ROOT)) + "\">"
                                          + esc(method) + "</span> ")
               + esc(route) + "</td>";
    }

    private static String codeCell(String text) {
        return "<td class=\"main code\" title=\"" + esc(text) + "\">" + esc(truncate(text, 200)) + "</td>";
    }

    private static String exceptionCell(MetricRow row) {
        String key = row.key();
        int at = key.indexOf(" @ ");
        String type = at < 0 ? key : key.substring(0, at);
        String location = at < 0 ? null : key.substring(at + 3);
        return "<td class=\"main\" title=\"" + esc(row.sample() == null ? key : key + "\n" + row.sample()) + "\">"
               + "<strong>" + esc(simpleClassName(type)) + "</strong>"
               + (location == null ? "" : " <span class=\"muted small\">" + esc(location) + "</span>")
               + (row.sample() == null ? "" : "<div class=\"muted small sub\">" + esc(truncate(row.sample(), 160)) + "</div>")
               + "</td>";
    }

    private static String taskCell(String task, String error) {
        int hash = task.indexOf('#');
        String shortTask = hash < 0 ? simpleClassName(task) : simpleClassName(task.substring(0, hash)) + task.substring(hash);
        return "<td class=\"main\" title=\"" + esc(error == null ? task : task + "\n" + error) + "\">" + esc(shortTask)
               + (error == null ? "" : "<div class=\"muted small sub\">" + esc(truncate(error, 160)) + "</div>")
               + "</td>";
    }

    private static String outgoingCell(MetricRow row) {
        return routeCell(row);
    }

    private static String countCell(MetricRow row) {
        return "<td class=\"num\">" + row.hits() + "</td>";
    }

    private static String msCell(long ms) {
        return "<td class=\"num\">" + ms + " ms</td>";
    }

    private static String agoCell(long now, long seen) {
        return "<td class=\"num when\">" + esc(ago(now, seen)) + "</td>";
    }

    // ---------------------------------------------------------------- formatting

    private static String fact(String label, String valueHtml) {
        return "<div><dt>" + esc(label) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    private static String toned(long value, String tone) {
        return value == 0 ? "0" : "<span class=\"badge " + tone + "\">" + value + "</span>";
    }

    private static String perMinute(long requests, long seconds) {
        if (seconds <= 0) {
            return "—";
        }
        double rate = requests * 60.0 / seconds;
        return rate >= 10 ? String.valueOf(Math.round(rate)) : String.format(java.util.Locale.ROOT, "%.1f", rate);
    }

    private static String percent(long permille) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", permille / 10.0);
    }

    static String ago(long now, long seen) {
        long seconds = Math.max(0, now - seen);
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86_400) + "d ago";
    }

    private static String barLabel(long barSeconds) {
        return barSeconds % 3600 == 0 ? (barSeconds / 3600) + " h" : (barSeconds / 60) + " min";
    }

    private static String time(ViewContext ctx, Instant instant, Period period) {
        return (period == Period.WEEK ? DAY_TIME : TIME).format(instant.atZone(ctx.zone()));
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
