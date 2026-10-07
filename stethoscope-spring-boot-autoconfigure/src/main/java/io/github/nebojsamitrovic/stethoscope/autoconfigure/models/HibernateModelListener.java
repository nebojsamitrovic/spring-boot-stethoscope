package io.github.nebojsamitrovic.stethoscope.autoconfigure.models;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;

/**
 * Records entity inserts, updates and deletes as {@link EntryType#MODEL} entries. Values are rendered
 * with Hibernate's own loggable representation, which never initializes lazy associations;
 * collections are skipped and secret-looking attributes (e.g. {@code password}) are masked.
 */
public class HibernateModelListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    static final String CREATED = "created";
    static final String UPDATED = "updated";
    static final String DELETED = "deleted";

    private static final int MAX_VALUE = 300;

    private final Supplier<Recorder> recorder;
    private final Redactor redactor;

    public HibernateModelListener(Supplier<Recorder> recorder, Redactor redactor) {
        this.recorder = recorder;
        this.redactor = redactor;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(CREATED, event.getPersister(), event.getId(), null, event.getState(), null);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        record(UPDATED, event.getPersister(), event.getId(), event.getOldState(), event.getState(), event.getDirtyProperties());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        record(DELETED, event.getPersister(), event.getId(), null, null, null);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void record(String action, EntityPersister persister, Object id, Object[] oldState, Object[] state, int[] dirty) {
        try {
            Recorder target = recorder.get();
            if (target == null || !target.isRecording()) {
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.ENTITY, persister.getEntityName());
            content.put(Entry.Content.ENTITY_ID, String.valueOf(id));
            content.put(Entry.Content.ACTION, action);
            Map<String, String> changes = changes(persister, oldState, state, dirty);
            if (!changes.isEmpty()) {
                content.put(Entry.Content.CHANGES, changes);
            }
            target.record(EntryType.MODEL, content, Set.of(action));
        } catch (RuntimeException ignored) {
            // never break persistence because of the debugger
        }
    }

    private Map<String, String> changes(EntityPersister persister, Object[] oldState, Object[] state, int[] dirty) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (state == null) {
            return changes;
        }
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        int[] indexes = dirty;
        if (indexes == null) {
            indexes = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                indexes[i] = i;
            }
        }
        for (int i : indexes) {
            if (i < 0 || i >= names.length || types[i].isCollectionType()) {
                continue;
            }
            String after = render(names[i], types[i], state[i], persister);
            if (oldState != null && i < oldState.length) {
                changes.put(names[i], render(names[i], types[i], oldState[i], persister) + " → " + after);
            } else if (state[i] != null) {
                changes.put(names[i], after);
            }
        }
        return changes;
    }

    private String render(String name, Type type, Object value, EntityPersister persister) {
        if (value == null) {
            return "null";
        }
        if (redactor.isSensitiveParameter(name)) {
            return Redactor.MASK;
        }
        String text;
        try {
            text = type.toLoggableString(value, persister.getFactory());
        } catch (RuntimeException ex) {
            text = "<" + value.getClass().getSimpleName() + ">";
        }
        return text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "…" : text;
    }
}
