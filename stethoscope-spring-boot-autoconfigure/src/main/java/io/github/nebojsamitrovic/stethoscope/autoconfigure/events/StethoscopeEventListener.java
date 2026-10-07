package io.github.nebojsamitrovic.stethoscope.autoconfigure.events;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.Bodies;
import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Records application events. Both classic {@link ApplicationEvent} subclasses and plain objects
 * published with {@code ApplicationEventPublisher.publishEvent(Object)} are supported; framework
 * events are skipped via {@code stethoscope.events.ignore-packages}.
 */
public class StethoscopeEventListener implements ApplicationListener<ApplicationEvent> {

    private static final int MAX_PAYLOAD = 4000;

    private final Recorder recorder;
    private final List<String> ignorePackages;

    public StethoscopeEventListener(Recorder recorder, List<String> ignorePackages) {
        this.recorder = recorder;
        this.ignorePackages = ignorePackages == null ? List.of() : List.copyOf(ignorePackages);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            if (!recorder.isRecording()) {
                return;
            }
            Object subject = event instanceof PayloadApplicationEvent<?> payloadEvent ? payloadEvent.getPayload() : event;
            String type = subject.getClass().getName();
            if (isIgnored(type)) {
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Entry.Content.EVENT_CLASS, type);
            content.put(Entry.Content.PAYLOAD, Bodies.abbreviate(subject, MAX_PAYLOAD));
            if (!(event instanceof PayloadApplicationEvent<?>) && event.getSource() != null) {
                content.put(Entry.Content.SOURCE, event.getSource().getClass().getName());
            }
            recorder.record(EntryType.EVENT, content, Set.of());
        } catch (RuntimeException ignored) {
            // never break event publishing because of the debugger
        }
    }

    private boolean isIgnored(String className) {
        for (String prefix : ignorePackages) {
            if (prefix != null && !prefix.isBlank() && className.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }
}
