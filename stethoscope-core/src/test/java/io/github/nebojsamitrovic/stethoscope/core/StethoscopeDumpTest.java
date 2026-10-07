package io.github.nebojsamitrovic.stethoscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StethoscopeDumpTest {

    private final InMemoryEntryStore store = new InMemoryEntryStore(10);
    private final Recorder recorder = new Recorder(store, RecorderSettings.defaults());

    @AfterEach
    void tearDown() {
        Stethoscope.uninstall(recorder);
        BatchContext.end();
    }

    @Test
    void isANoOpWhenNotInstalled() {
        assertEquals("x", Stethoscope.dump("x"));
        assertEquals(0, store.count(EntryType.DUMP));
    }

    @Test
    void recordsValuesWithCallerAndBatch() {
        Stethoscope.install(recorder, null);
        Batch batch = BatchContext.start();

        Object value = new int[] {1, 2};
        assertSame(value, Stethoscope.dump(value));
        Stethoscope.dump("a", null, List.of(1));

        List<Entry> dumps = store.list(EntryQuery.of(EntryType.DUMP));
        assertEquals(2, dumps.size());
        assertEquals(List.of("a", "null", "[1]"), dumps.get(0).get(Entry.Content.VALUES));
        assertEquals(List.of("[1, 2]"), dumps.get(1).get(Entry.Content.VALUES));
        assertEquals(batch.id(), dumps.get(0).batchId());
        assertTrue(dumps.get(0).getString(Entry.Content.LOCATION, "").contains("StethoscopeDumpTest.recordsValuesWithCallerAndBatch"));
    }

    @Test
    void usesTheInstalledRenderer() {
        Stethoscope.install(recorder, value -> "<" + value + ">");
        Stethoscope.dump("x");
        assertEquals(List.of("<x>"), store.list(EntryQuery.of(EntryType.DUMP)).get(0).get(Entry.Content.VALUES));
    }
}
