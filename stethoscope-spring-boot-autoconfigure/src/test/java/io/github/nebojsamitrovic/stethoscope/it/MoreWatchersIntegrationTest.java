package io.github.nebojsamitrovic.stethoscope.it;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class, properties = {
        "stethoscope.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:stethoscope-more;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class MoreWatchersIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntryStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists item (id int primary key, name varchar(50))");
        store.clear();
    }

    @Test
    void asyncJobsGetTheirOwnBatchLinkedToTheRequest() throws Exception {
        mvc.perform(get("/async")).andExpect(status().isOk());

        Entry request = onlyRequest("/async");
        Entry job = store.list(EntryQuery.of(EntryType.JOB)).stream()
                .filter(j -> request.batchId().equals(j.getString(Entry.Content.PARENT_BATCH, null)))
                .findFirst().orElseThrow();
        assertThat(job.getString(Entry.Content.TASK, "")).isEqualTo(TestApplication.ReportService.class.getName() + "#countItems");
        assertThat(job.<Boolean>get(Entry.Content.SUCCESS)).isTrue();
        assertThat(job.<Integer>get(Entry.Content.QUERY_COUNT)).isEqualTo(1);
        assertThat(job.batchId()).isNotEqualTo(request.batchId());

        List<Entry> jobBatch = store.batch(job.batchId());
        assertThat(jobBatch).extracting(Entry::type).contains(EntryType.QUERY, EntryType.LOG, EntryType.JOB);
        assertThat(store.batch(request.batchId())).noneMatch(e -> e.type() == EntryType.QUERY);

        // the request page lists the job, the job page links back to the request
        mvc.perform(get("/stethoscope/entries/" + request.id()))
                .andExpect(content().string(Matchers.containsString("/stethoscope/entries/" + job.id())));
        mvc.perform(get("/stethoscope/entries/" + job.id()))
                .andExpect(content().string(Matchers.containsString("/stethoscope/entries/" + request.id())));
    }

    @Test
    void entityLifecycleIsRecordedWithChangesAndMaskedSecrets() throws Exception {
        mvc.perform(post("/customers/lifecycle")).andExpect(status().isOk());

        List<Entry> models = ofType(store.batch(onlyRequest("/customers/lifecycle").batchId()), EntryType.MODEL);
        assertThat(models).extracting(e -> e.getString(Entry.Content.ACTION, ""))
                .containsExactly("created", "updated", "deleted");
        assertThat(models).allSatisfy(e -> assertThat(e.getString(Entry.Content.ENTITY, "")).isEqualTo(Customer.class.getName()));

        Map<String, String> created = models.get(0).get(Entry.Content.CHANGES);
        assertThat(created).containsEntry("name", "ana").containsEntry("password", Redactor.MASK);
        Map<String, String> updated = models.get(1).get(Entry.Content.CHANGES);
        assertThat(updated).containsExactly(Map.entry("name", "ana → Ana"));
    }

    @Test
    void redisCommandsAreRecorded() throws Exception {
        mvc.perform(get("/redis")).andExpect(status().isOk()).andExpect(content().string("hello"));

        List<Entry> commands = ofType(store.batch(onlyRequest("/redis").batchId()), EntryType.REDIS);
        assertThat(commands).extracting(e -> e.getString(Entry.Content.COMMAND, "")).containsExactly("SET", "GET");
        assertThat(commands.get(0).getString(Entry.Content.ARGS, "")).isEqualTo("greeting hello");
        assertThat(commands.get(1).getString(Entry.Content.ARGS, "")).isEqualTo("greeting");
    }

    @Test
    void securityDecisionsAreRecorded() throws Exception {
        mvc.perform(post("/security")).andExpect(status().isOk());

        List<Entry> security = ofType(store.batch(onlyRequest("/security").batchId()), EntryType.SECURITY);
        assertThat(security).hasSize(2);
        Entry denied = security.get(0);
        assertThat(denied.getString(Entry.Content.RESULT, "")).isEqualTo("denied");
        assertThat(denied.getString(Entry.Content.PRINCIPAL, "")).isEqualTo("ana");
        assertThat(denied.getString(Entry.Content.RESOURCE, "")).isEqualTo("POST /security");
        assertThat(denied.<List<String>>get(Entry.Content.AUTHORITIES)).containsExactly("ROLE_USER");
        Entry failed = security.get(1);
        assertThat(failed.getString(Entry.Content.RESULT, "")).isEqualTo("failed");
        assertThat(failed.getString(Entry.Content.PRINCIPAL, "")).isEqualTo("mallory");
        assertThat(failed.getString(Entry.Content.ERROR, "")).contains("Bad credentials");
        // Spring Security events are not duplicated under "Events"
        assertThat(ofType(store.batch(failed.batchId()), EntryType.EVENT)).isEmpty();
    }

    @Test
    void everyNewSectionRenders() throws Exception {
        mvc.perform(get("/async")).andExpect(status().isOk());
        mvc.perform(post("/customers/lifecycle")).andExpect(status().isOk());
        mvc.perform(get("/redis")).andExpect(status().isOk());
        mvc.perform(post("/security")).andExpect(status().isOk());
        for (EntryType type : List.of(EntryType.JOB, EntryType.MODEL, EntryType.REDIS, EntryType.SECURITY, EntryType.MESSAGE)) {
            mvc.perform(get("/stethoscope/" + type.section())).andExpect(status().isOk());
        }
        for (Entry entry : store.list(new EntryQuery(null, null, null, 500))) {
            mvc.perform(get("/stethoscope/entries/" + entry.id())).andExpect(status().isOk());
        }
    }

    private Entry onlyRequest(String uri) {
        List<Entry> requests = store.list(new EntryQuery(EntryType.REQUEST, null, null, 50)).stream()
                .filter(e -> uri.equals(e.get(Entry.Content.URI)))
                .toList();
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static List<Entry> ofType(List<Entry> entries, EntryType type) {
        return entries.stream().filter(e -> e.type() == type).toList();
    }
}
