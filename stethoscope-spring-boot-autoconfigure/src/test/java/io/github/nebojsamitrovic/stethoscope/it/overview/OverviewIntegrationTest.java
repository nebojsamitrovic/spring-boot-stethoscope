package io.github.nebojsamitrovic.stethoscope.it.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.Metric;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricRow;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricsScheduler;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricsStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OverviewTestApplication.class, properties = {
        "stethoscope.enabled=true",
        "stethoscope.store-entries=false",
        "stethoscope.requests.slow-threshold=0ms",
        "stethoscope.queries.slow-threshold=0ms",
        "stethoscope.basic-auth.username=admin",
        "stethoscope.basic-auth.password=s3cret",
        "stethoscope.metrics.flush-interval=1h",
        "spring.datasource.url=jdbc:h2:mem:overview-it;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class OverviewIntegrationTest {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));

    @Autowired
    MockMvc mvc;

    @Autowired
    MetricsScheduler scheduler;

    @Autowired
    MetricsStore store;

    @Autowired
    EntryStore entries;

    @Test
    void requestsQueriesAndExceptionsEndUpInTheDatabaseAndOnThePage() throws Exception {
        mvc.perform(get("/orders/1")).andExpect(status().isOk());
        mvc.perform(get("/orders/2")).andExpect(status().isOk());
        assertThatThrownBy(() -> mvc.perform(get("/orders/3/fail"))).hasRootCauseMessage("order 3 is locked");

        scheduler.flush();

        assertThat(store.top(Metric.SLOW_REQUEST, MetricRow.MINUTE, 0, MetricsStore.Order.HITS, 10))
                .extracting(MetricRow::key, MetricRow::hits)
                .contains(org.assertj.core.groups.Tuple.tuple("GET /orders/{id}", 2L));
        assertThat(store.top(Metric.SLOW_QUERY, MetricRow.MINUTE, 0, MetricsStore.Order.HITS, 10))
                .extracting(MetricRow::key)
                .contains("select ? || cast(? as varchar)")
                .noneMatch(sql -> sql.contains("stethoscope_metrics"));
        assertThat(store.top(Metric.EXCEPTION, MetricRow.MINUTE, 0, MetricsStore.Order.HITS, 10))
                .anySatisfy(row -> {
                    assertThat(row.key()).startsWith("java.lang.IllegalStateException @ ");
                    assertThat(row.sample()).isEqualTo("order 3 is locked");
                });
        assertThat(store.top(Metric.THROUGHPUT, MetricRow.HOUR, 0, MetricsStore.Order.HITS, 10))
                .extracting(MetricRow::key).contains("2xx", "5xx");

        assertThat(entries.count(EntryType.REQUEST)).as("store-entries=false keeps nothing").isZero();

        String page = mvc.perform(get("/stethoscope/overview").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Overview", "Slow requests", "/orders/{id}", "IllegalStateException", "order 3 is locked");
    }

    @Test
    void dashboardAsksForCredentialsAndOpensOnTheOverview() throws Exception {
        mvc.perform(get("/stethoscope/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));

        mvc.perform(get("/stethoscope").header("Authorization", AUTH))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/stethoscope/overview"));

        mvc.perform(get("/stethoscope/overview/cards?period=7d").header("Authorization", AUTH)).andExpect(status().isOk());
    }
}
