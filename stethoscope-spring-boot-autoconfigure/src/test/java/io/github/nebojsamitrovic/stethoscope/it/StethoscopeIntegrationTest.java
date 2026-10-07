package io.github.nebojsamitrovic.stethoscope.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryQuery;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class, properties = {
        "stethoscope.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:stethoscope-it;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class StethoscopeIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntryStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists item (id int primary key, name varchar(50))");
        jdbc.update("delete from item");
        for (int i = 1; i <= 6; i++) {
            jdbc.update("insert into item (id, name) values (?, ?)", i, "item-" + i);
        }
        store.clear();
    }

    @Test
    void requestQueriesAndExceptionShareOneBatch() throws Exception {
        mvc.perform(get("/items/fail")).andExpect(status().isConflict());

        Entry request = onlyRequest("/items/fail");
        assertThat(request.batchId()).isNotNull();
        assertThat(request.hasTag(Entry.Tags.HAS_EXCEPTION)).isTrue();
        assertThat(request.<Integer>get(Entry.Content.QUERY_COUNT)).isEqualTo(1);

        List<Entry> batch = store.batch(request.batchId());
        assertThat(batch).extracting(Entry::type)
                .containsExactlyInAnyOrder(EntryType.QUERY, EntryType.EXCEPTION, EntryType.REQUEST);

        Entry exception = batch.stream().filter(e -> e.type() == EntryType.EXCEPTION).findFirst().orElseThrow();
        assertThat(exception.<String>get(Entry.Content.EXCEPTION_CLASS)).isEqualTo(IllegalStateException.class.getName());
        assertThat(exception.<Boolean>get(Entry.Content.HANDLED)).isTrue();

        Entry query = batch.stream().filter(e -> e.type() == EntryType.QUERY).findFirst().orElseThrow();
        assertThat(query.<String>get(Entry.Content.SQL)).containsIgnoringCase("from item");
    }

    @Test
    void unhandledExceptionIsRecordedAsFailedRequest() {
        assertThatThrownBy(() -> mvc.perform(get("/items/crash"))).hasRootCauseInstanceOf(IllegalArgumentException.class);

        Entry request = onlyRequest("/items/crash");
        assertThat(request.<Integer>get(Entry.Content.STATUS)).isEqualTo(500);
        assertThat(request.hasTag(Entry.Tags.FAILED)).isTrue();

        Entry exception = store.batch(request.batchId()).stream()
                .filter(e -> e.type() == EntryType.EXCEPTION).findFirst().orElseThrow();
        assertThat(exception.<Boolean>get(Entry.Content.HANDLED)).isFalse();
    }

    @Test
    void repeatedQueriesAreTaggedNPlusOne() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isOk());

        Entry request = onlyRequest("/items");
        assertThat(request.hasTag(Entry.Tags.N_PLUS_ONE)).isTrue();
        assertThat(request.<Integer>get(Entry.Content.QUERY_COUNT)).isEqualTo(7);
        assertThat(request.<Map<String, Integer>>get(Entry.Content.DUPLICATE_QUERIES)).isNotEmpty();
    }

    @Test
    void secretsAreRedacted() throws Exception {
        mvc.perform(post("/login")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test\",\"password\":\"test\"}"))
                .andExpect(status().isOk());

        Entry request = onlyRequest("/login");
        Map<String, String> headers = request.get(Entry.Content.REQUEST_HEADERS);
        assertThat(headers).containsEntry("Authorization", Redactor.MASK);
        String body = request.get(Entry.Content.REQUEST_BODY);
        assertThat(body).contains("\"username\":\"test\"").contains("\"password\":\"" + Redactor.MASK + "\"");
    }

    @Test
    void dashboardIsServedToAllowedIps() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isOk());

        mvc.perform(get("/stethoscope/requests"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/items")));
    }

    @Test
    void dashboardIsForbiddenForOtherIps() throws Exception {
        mvc.perform(get("/stethoscope/requests").with(request -> {
                    request.setRemoteAddr("10.1.2.3");
                    return request;
                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardRequestsAreNotRecorded() throws Exception {
        mvc.perform(get("/stethoscope/requests")).andExpect(status().isOk());

        assertThat(store.count(EntryType.REQUEST)).isZero();
    }

    private Entry onlyRequest(String uri) {
        List<Entry> requests = store.list(new EntryQuery(EntryType.REQUEST, null, null, 50)).stream()
                .filter(e -> uri.equals(e.get(Entry.Content.URI)))
                .toList();
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }
}
