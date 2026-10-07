package io.github.nebojsamitrovic.stethoscope.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryQuery;
import io.github.nebojsamitrovic.stethoscope.core.EntryStore;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class, properties = {
        "stethoscope.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:stethoscope-watchers;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class WatchersIntegrationTest {

    static final HttpServer SERVER = startServer();

    @DynamicPropertySource
    static void externalUrl(DynamicPropertyRegistry registry) {
        registry.add("test.external-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EntryStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestApplication.CapturingMailSender mailSender;

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists item (id int primary key, name varchar(50))");
        store.clear();
    }

    @AfterEach
    void tearDown() {
        TestApplication.Cleanup.ENABLED.set(false);
    }

    @Test
    void logsAreLinkedToTheRequest() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isOk());

        Entry request = onlyRequest("/items");
        Entry log = ofType(store.batch(request.batchId()), EntryType.LOG).stream()
                .filter(e -> e.getString(Entry.Content.MESSAGE, "").equals("loading 0 items"))
                .findFirst().orElseThrow();
        assertThat(log.getString(Entry.Content.LEVEL, "")).isEqualTo("INFO");
        assertThat(log.getString(Entry.Content.LOGGER, "")).isEqualTo(TestApplication.class.getName());
        assertThat(log.hasTag("info")).isTrue();
    }

    @Test
    void loggedExceptionIsRecordedOnceAsHandledException() throws Exception {
        mvc.perform(get("/items/logged-error")).andExpect(status().isOk());

        List<Entry> batch = store.batch(onlyRequest("/items/logged-error").batchId());
        assertThat(ofType(batch, EntryType.LOG)).anySatisfy(e -> {
            assertThat(e.hasTag("error")).isTrue();
            assertThat(e.getString(Entry.Content.STACK_TRACE, "")).contains("UnsupportedOperationException");
        });
        List<Entry> exceptions = ofType(batch, EntryType.EXCEPTION);
        assertThat(exceptions).hasSize(1);
        assertThat(exceptions.get(0).<Boolean>get(Entry.Content.HANDLED)).isTrue();
    }

    @Test
    void restClientCallsAreRecordedWithRedaction() throws Exception {
        mvc.perform(get("/external")).andExpect(status().isOk()).andExpect(content().string("echo:{\"hello\":\"world\"}"));

        Entry call = onlyOfType(store.batch(onlyRequest("/external").batchId()), EntryType.HTTP_CLIENT);
        assertThat(call.getString(Entry.Content.METHOD, "")).isEqualTo("POST");
        assertThat(call.getString(Entry.Content.URL, "")).endsWith("/echo?api_key=" + Redactor.MASK);
        assertThat(call.<Integer>get(Entry.Content.STATUS)).isEqualTo(200);
        assertThat(call.<Map<String, String>>get(Entry.Content.REQUEST_HEADERS)).containsEntry("Authorization", Redactor.MASK);
        assertThat(call.getString(Entry.Content.REQUEST_BODY, "")).isEqualTo("{\"hello\":\"world\"}");
        assertThat(call.getString(Entry.Content.RESPONSE_BODY, "")).isEqualTo("echo:{\"hello\":\"world\"}");
    }

    @Test
    void webClientCallsAreRecorded() throws Exception {
        mvc.perform(get("/external-reactive")).andExpect(status().isOk());

        Entry call = onlyOfType(store.batch(onlyRequest("/external-reactive").batchId()), EntryType.HTTP_CLIENT);
        assertThat(call.getString(Entry.Content.METHOD, "")).isEqualTo("GET");
        assertThat(call.<Integer>get(Entry.Content.STATUS)).isEqualTo(200);
    }

    @Test
    void cacheMissThenHitIsRecorded() throws Exception {
        mvc.perform(get("/prices/7")).andExpect(status().isOk()).andExpect(content().string("140"));

        List<Entry> cache = ofType(store.batch(onlyRequest("/prices/7").batchId()), EntryType.CACHE);
        assertThat(cache).extracting(e -> e.getString(Entry.Content.OPERATION, ""))
                .containsExactly("miss", "put", "hit");
        assertThat(cache).allSatisfy(e -> {
            assertThat(e.getString(Entry.Content.CACHE_NAME, "")).isEqualTo("prices");
            assertThat(e.getString(Entry.Content.KEY, "")).isEqualTo("7");
        });
    }

    @Test
    void applicationEventsAreRecordedButFrameworkEventsAreNot() throws Exception {
        mvc.perform(post("/orders")).andExpect(status().isOk());

        Entry event = onlyOfType(store.batch(onlyRequest("/orders").batchId()), EntryType.EVENT);
        assertThat(event.getString(Entry.Content.EVENT_CLASS, "")).isEqualTo(TestApplication.OrderPlaced.class.getName());
        assertThat(event.getString(Entry.Content.PAYLOAD, "")).contains("orderId=42");
        assertThat(store.list(EntryQuery.of(EntryType.EVENT)))
                .noneMatch(e -> e.getString(Entry.Content.EVENT_CLASS, "").startsWith("org.springframework."));
    }

    @Test
    void mailIsRecordedAndStillSent() throws Exception {
        int before = mailSender.sent().size();
        mvc.perform(post("/mail")).andExpect(status().isOk());

        assertThat(mailSender.sent()).hasSize(before + 1);
        Entry mail = onlyOfType(store.batch(onlyRequest("/mail").batchId()), EntryType.MAIL);
        assertThat(mail.getString(Entry.Content.SUBJECT, "")).isEqualTo("Your order #42");
        assertThat(mail.<List<String>>get(Entry.Content.TO)).containsExactly("ana@example.com");
        assertThat(mail.getString(Entry.Content.TEXT_BODY, "")).isEqualTo("Thanks for your order");
        assertThat(mail.getString(Entry.Content.HTML_BODY, "")).contains("<h1>Thanks</h1>");
        assertThat(mail.<List<String>>get(Entry.Content.ATTACHMENTS)).containsExactly("invoice.txt");

        // the HTML preview is escaped into a sandboxed iframe, never inlined
        mvc.perform(get("/stethoscope/entries/" + mail.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("<iframe class=\"mail-preview\" sandbox")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<script>alert(1)</script>"))));
    }

    @Test
    void dumpsAreRecordedAsJson() throws Exception {
        mvc.perform(get("/dump")).andExpect(status().isOk());

        Entry dump = onlyOfType(store.batch(onlyRequest("/dump").batchId()), EntryType.DUMP);
        assertThat(dump.<List<String>>get(Entry.Content.VALUES).get(0)).contains("\"cart\" : [ 1, 2, 3 ]");
        assertThat(dump.getString(Entry.Content.LOCATION, "")).contains("ItemController.dump");
    }

    @Test
    void scheduledRunsGetTheirOwnBatch() {
        TestApplication.Cleanup.ENABLED.set(true);

        Entry run = await().atMost(Duration.ofSeconds(5)).until(
                () -> store.list(EntryQuery.of(EntryType.SCHEDULED)).stream().findFirst().orElse(null),
                java.util.Objects::nonNull);
        TestApplication.Cleanup.ENABLED.set(false);

        assertThat(run.getString(Entry.Content.TASK, "")).isEqualTo(TestApplication.Cleanup.class.getName() + "#purge");
        assertThat(run.<Boolean>get(Entry.Content.SUCCESS)).isFalse();
        assertThat(run.hasTag(Entry.Tags.FAILED)).isTrue();
        assertThat(run.<Integer>get(Entry.Content.QUERY_COUNT)).isEqualTo(1);

        List<Entry> batch = store.batch(run.batchId());
        assertThat(batch).extracting(Entry::type)
                .contains(EntryType.QUERY, EntryType.LOG, EntryType.EXCEPTION, EntryType.SCHEDULED);
        assertThat(ofType(batch, EntryType.EXCEPTION)).hasSize(1);
    }

    @Test
    void everySectionRenders() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isOk());
        mvc.perform(post("/orders")).andExpect(status().isOk());
        mvc.perform(get("/dump")).andExpect(status().isOk());
        for (EntryType type : EntryType.values()) {
            mvc.perform(get("/stethoscope/" + type.section())).andExpect(status().isOk());
            mvc.perform(get("/stethoscope/" + type.section() + "/rows")).andExpect(status().isOk());
        }
        for (Entry entry : store.list(new EntryQuery(null, null, null, 500))) {
            mvc.perform(get("/stethoscope/entries/" + entry.id())).andExpect(status().isOk());
        }
        mvc.perform(get("/stethoscope/nope")).andExpect(status().isNotFound());
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

    private static Entry onlyOfType(List<Entry> entries, EntryType type) {
        List<Entry> matching = ofType(entries, type);
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/echo", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                byte[] response = ("echo:" + new String(request, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
