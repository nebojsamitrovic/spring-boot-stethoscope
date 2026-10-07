package io.github.nebojsamitrovic.stethoscope.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Static entry point for application code, the Java counterpart of Laravel's {@code dump()}.
 *
 * <pre>
 * Stethoscope.dump(order);                       // shows up under "Dumps", linked to the current request
 * var total = Stethoscope.dump(cart.total());    // returns its argument, so it can wrap expressions
 * Stethoscope.dump("user", user, "roles", roles);
 * </pre>
 *
 * <p>When Stethoscope is disabled (or not on the classpath at runtime of a test), calls are no-ops.
 */
public final class Stethoscope {

    private static final int MAX_LENGTH = 20_000;

    private static volatile Recorder recorder;
    private static volatile Function<Object, String> renderer = Stethoscope::defaultRender;

    private Stethoscope() {
    }

    /**
     * Connects the static API to a recorder. Called by the Spring Boot auto-configuration.
     *
     * @param renderer turns a value into text, e.g. pretty JSON; {@code null} keeps the default
     */
    public static void install(Recorder target, Function<Object, String> renderer) {
        Stethoscope.recorder = target;
        Stethoscope.renderer = renderer == null ? Stethoscope::defaultRender : renderer;
    }

    /** Disconnects, but only if {@code target} is still the installed recorder. */
    public static void uninstall(Recorder target) {
        if (recorder == target) {
            recorder = null;
            renderer = Stethoscope::defaultRender;
        }
    }

    /** Records one value and returns it unchanged. */
    public static <T> T dump(T value) {
        record(new Object[] {value});
        return value;
    }

    /** Records several values as one entry. */
    public static void dump(Object... values) {
        record(values == null ? new Object[] {null} : values);
    }

    private static void record(Object[] values) {
        Recorder target = recorder;
        if (target == null || !target.isRecording()) {
            return;
        }
        try {
            Function<Object, String> render = renderer;
            List<String> rendered = new ArrayList<>(values.length);
            for (Object value : values) {
                rendered.add(truncate(safeRender(render, value)));
            }
            target.recordDump(rendered, callerLocation());
        } catch (RuntimeException ignored) {
            // never break the application because of the debugger
        }
    }

    private static String safeRender(Function<Object, String> render, Object value) {
        try {
            return render.apply(value);
        } catch (RuntimeException ex) {
            return defaultRender(value);
        }
    }

    static String defaultRender(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[] array) {
            return Arrays.deepToString(array);
        }
        if (value.getClass().isArray()) {
            return Arrays.deepToString(new Object[] {value}).replaceAll("^\\[|]$", "");
        }
        return String.valueOf(value);
    }

    private static String truncate(String text) {
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "… (truncated)" : text;
    }

    private static String callerLocation() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(f -> !f.getClassName().equals(Stethoscope.class.getName()))
                        .findFirst()
                        .map(f -> f.getClassName() + "." + f.getMethodName()
                                  + "(" + f.getFileName() + ":" + f.getLineNumber() + ")")
                        .orElse(null));
    }
}
