package io.github.nebojsamitrovic.stethoscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecorderTest {

    private final InMemoryEntryStore store = new InMemoryEntryStore(100);
    private final Recorder recorder = new Recorder(store, new RecorderSettings(50, 3, true, 5));

    @AfterEach
    void cleanUp() {
        BatchContext.end();
    }

    @Test
    void queriesInsideBatchShareBatchIdAndFeedSummary() {
        Batch batch = BatchContext.start();
        for (int i = 1; i <= 4; i++) {
            recorder.recordQuery("select * from post where author_id = " + i, List.of(), 1_000_000, true, null);
        }
        recorder.recordQuery("select * from author", List.of(), 80_000_000, true, "primary");

        List<Entry> queries = store.list(EntryQuery.of(EntryType.QUERY));
        assertEquals(5, queries.size());
        assertTrue(queries.stream().allMatch(e -> batch.id().equals(e.batchId())));
        assertTrue(queries.get(0).hasTag(Entry.Tags.SLOW), "80 ms >= 50 ms threshold");

        Map<String, Object> content = new LinkedHashMap<>();
        Set<String> tags = new HashSet<>();
        recorder.applyBatchSummary(batch, content, tags);

        assertEquals(5, content.get(Entry.Content.QUERY_COUNT));
        assertEquals(84L, content.get(Entry.Content.QUERY_TIME_MS));
        assertTrue(tags.contains(Entry.Tags.N_PLUS_ONE));
        assertEquals(Map.of("select * from post where author_id = ?", 4), content.get(Entry.Content.DUPLICATE_QUERIES));
    }

    @Test
    void sameExceptionIsRecordedOncePerBatch() {
        BatchContext.start();
        IllegalStateException failure = new IllegalStateException("boom", new RuntimeException("root cause"));

        recorder.recordException(failure, true);
        recorder.recordException(failure, false);

        List<Entry> exceptions = store.list(EntryQuery.of(EntryType.EXCEPTION));
        assertEquals(1, exceptions.size());
        Entry entry = exceptions.get(0);
        assertEquals("java.lang.IllegalStateException", entry.get(Entry.Content.EXCEPTION_CLASS));
        assertEquals("boom", entry.get(Entry.Content.MESSAGE));
        assertTrue(entry.getString(Entry.Content.STACK_TRACE, "").contains("Caused by: java.lang.RuntimeException: root cause"));
        assertTrue(entry.getString(Entry.Content.LOCATION, "").contains("RecorderTest"));
    }

    @Test
    void pausedRecorderStoresNothing() {
        recorder.pause();
        assertNull(recorder.record(EntryType.REQUEST, Map.of(), Set.of()));
        recorder.recordQuery("select 1", List.of(), 0, true, null);
        assertEquals(0, store.list(new EntryQuery(null, null, null, 0)).size());

        recorder.resume();
        assertTrue(recorder.isRecording());
    }

    @Test
    void entriesOutsideBatchHaveNoBatchId() {
        Entry entry = recorder.record(EntryType.REQUEST, Map.of(), Set.of());
        assertNull(entry.batchId());
    }

    @Test
    void parametersCanBeDisabled() {
        Recorder noParams = new Recorder(store, new RecorderSettings(100, 5, false, 5));
        noParams.recordQuery("select * from users where email = ?", List.of("a@b.c"), 0, true, null);
        assertFalse(store.list(EntryQuery.of(EntryType.QUERY)).get(0).content().containsKey(Entry.Content.PARAMETERS));
    }

    @Test
    void sameExceptionIsRecordedOnceEvenWhenWrapped() {
        IllegalStateException cause = new IllegalStateException("boom");
        recorder.recordException(cause, true);
        recorder.recordException(new RuntimeException("wrapper", cause), false);
        recorder.recordException(cause, false);

        assertEquals(1, store.count(EntryType.EXCEPTION));
    }
}
