package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricsStore;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryQuery;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the dashboard: full pages for normal navigation, row fragments for htmx polling and
 * filtering, plus the bundled CSS and htmx script. Access is enforced by
 * {@link io.github.nebojsamitrovic.stethoscope.autoconfigure.web.RequestWatcherFilter}.
 */
@Controller
@RequestMapping("${stethoscope.path:/stethoscope}")
public class StethoscopeUiController {

    private static final int PAGE_SIZE = 100;

    private final Recorder recorder;
    private final EntryStore store;
    private final DashboardResponses responses;
    private final OverviewReader overview;

    public StethoscopeUiController(Recorder recorder, StethoscopeProperties properties) {
        this(recorder, properties, null);
    }

    /** @param metrics where the overview reads from; {@code null} when no metrics are collected */
    public StethoscopeUiController(Recorder recorder, StethoscopeProperties properties, MetricsStore metrics) {
        this.recorder = recorder;
        this.store = recorder.store();
        this.responses = new DashboardResponses(recorder, properties, metrics != null);
        this.overview = metrics == null ? null : new OverviewReader(metrics, properties, Clock.systemUTC());
    }

    @GetMapping({"", "/"})
    public ResponseEntity<Void> index(HttpServletRequest request) {
        return responses.redirectToStart(request);
    }

    @GetMapping("/overview")
    public ResponseEntity<String> overview(
            @RequestParam(name = "period", required = false) String period, HttpServletRequest request) {
        if (overview == null) {
            return responses.notFound(request);
        }
        return responses.html(OverviewViews.page(responses.context(request), overview.read(Period.fromSlug(period))));
    }

    @GetMapping("/overview/cards")
    public ResponseEntity<String> overviewCards(
            @RequestParam(name = "period", required = false) String period, HttpServletRequest request) {
        if (overview == null) {
            return responses.notFound(request);
        }
        return responses.html(OverviewViews.cards(responses.context(request), overview.read(Period.fromSlug(period))));
    }

    @GetMapping("/{section:[a-z][a-z-]*}")
    public ResponseEntity<String> list(
            @PathVariable("section") String section,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "tag", required = false) String tag,
            HttpServletRequest request) {
        EntryType type = ViewContext.fromSection(section);
        if (type == null) {
            return responses.notFound(request);
        }
        List<Entry> entries = store.list(new EntryQuery(type, tag, search, PAGE_SIZE));
        return responses.html(Views.listPage(responses.context(request), type, entries, search, tag));
    }

    @GetMapping("/{section:[a-z][a-z-]*}/rows")
    public ResponseEntity<String> rows(
            @PathVariable("section") String section,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "tag", required = false) String tag,
            HttpServletRequest request) {
        EntryType type = ViewContext.fromSection(section);
        if (type == null) {
            return responses.notFound(request);
        }
        List<Entry> entries = store.list(new EntryQuery(type, tag, search, PAGE_SIZE));
        return responses.html(Views.rows(responses.context(request), type, entries));
    }

    @GetMapping("/entries/{id:\\d+}")
    public ResponseEntity<String> detail(@PathVariable("id") long id, HttpServletRequest request) {
        ViewContext ctx = responses.context(request);
        return store.find(id)
                .map(entry -> responses.html(Views.detail(ctx, entry, store.batch(entry.batchId()),
                        store.jobsDispatchedBy(entry.batchId()), store.dispatchingBatch(entry))))
                .orElseGet(() -> responses.notFound(ctx));
    }

    @PostMapping("/clear")
    public ResponseEntity<Void> clear() {
        store.clear();
        return responses.refresh();
    }

    @PostMapping("/pause")
    public ResponseEntity<Void> pause() {
        recorder.pause();
        return responses.refresh();
    }

    @PostMapping("/resume")
    public ResponseEntity<Void> resume() {
        recorder.resume();
        return responses.refresh();
    }

    @GetMapping("/assets/{name:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable("name") String name) {
        return responses.asset(name);
    }
}
