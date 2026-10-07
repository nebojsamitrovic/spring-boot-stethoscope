package io.github.nebojsamitrovic.stethoscope.autoconfigure.redis;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.util.ClassUtils;

/**
 * JDK proxies around Spring Data Redis connections that record every command as a
 * {@link EntryType#REDIS} entry.
 *
 * <p>{@code RedisConnection} exposes commands both directly ({@code connection.set(...)}) and through
 * command interfaces ({@code connection.stringCommands().set(...)}). Both paths are covered: the
 * command interfaces returned by a recorded connection are wrapped as well. Calls the real connection
 * makes on itself do not pass through the proxy, so nothing is recorded twice.
 */
public final class RecordingRedisConnections {

    private static final int MAX_ARGS = 1000;
    private static final int MAX_ARG = 200;

    /** Infrastructure methods that are not Redis commands. */
    private static final Set<String> NOT_COMMANDS = Set.of(
            "close", "isClosed", "getNativeConnection", "isQueueing", "isPipelined", "openPipeline", "closePipeline",
            "getSentinelConnection", "isSubscribed", "getSubscription", "getConvertPipelineAndTxResults",
            "translateExceptionIfPossible", "getClusterCommandExecutor", "getClusterNodes", "getClusterNodeForSlot",
            "getClusterNodeForKey", "getClusterSlotForKey", "getClusterInfo");

    private RecordingRedisConnections() {
    }

    /** Wraps a connection (or command interface) in a recording proxy that implements all of its interfaces. */
    static Object wrap(Object target, Supplier<Recorder> recorder) {
        if (target == null || Proxy.isProxyClass(target.getClass()) && Proxy.getInvocationHandler(target) instanceof Handler) {
            return target;
        }
        Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(target.getClass(), target.getClass().getClassLoader());
        if (interfaces.length == 0) {
            return target;
        }
        try {
            return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, new Handler(target, recorder));
        } catch (IllegalArgumentException ex) {
            // interfaces not visible from the target's class loader: leave the connection alone
            return target;
        }
    }

    private record Handler(Object target, Supplier<Recorder> recorder) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> method.invoke(target, args);
                };
            }
            boolean command = isCommand(method);
            long start = System.nanoTime();
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                if (command) {
                    record(method, args, System.nanoTime() - start, ex.getTargetException());
                }
                throw ex.getTargetException();
            }
            if (isCommandInterface(method.getReturnType())) {
                return wrap(result, recorder);
            }
            if (command) {
                record(method, args, System.nanoTime() - start, null);
            }
            return result;
        }

        private void record(Method method, Object[] args, long durationNanos, Throwable error) {
            try {
                Recorder target = recorder.get();
                if (target == null || !target.isRecording()) {
                    return;
                }
                String command = method.getName().toUpperCase(Locale.ROOT);
                Map<String, Object> content = new LinkedHashMap<>();
                content.put(Entry.Content.COMMAND, command);
                String rendered = renderArgs(args);
                if (!rendered.isEmpty()) {
                    content.put(Entry.Content.ARGS, rendered);
                }
                content.put(Entry.Content.DURATION_MS, durationNanos / 1_000_000);
                content.put(Entry.Content.SUCCESS, error == null);
                Set<String> tags = new HashSet<>();
                if (error != null) {
                    content.put(Entry.Content.ERROR, error.getClass().getName() + ": " + error.getMessage());
                    tags.add(Entry.Tags.FAILED);
                }
                target.record(EntryType.REDIS, content, tags);
            } catch (RuntimeException ignored) {
                // never break Redis access because of the debugger
            }
        }
    }

    private static boolean isCommand(Method method) {
        return !NOT_COMMANDS.contains(method.getName()) && !isCommandInterface(method.getReturnType());
    }

    /** {@code RedisStringCommands}, {@code RedisKeyCommands}, ... as returned by {@code stringCommands()}. */
    private static boolean isCommandInterface(Class<?> type) {
        return type.isInterface() && type.getName().startsWith("org.springframework.data.redis.connection.")
               && type.getSimpleName().endsWith("Commands");
    }

    static String renderArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>(args.length);
        for (Object arg : args) {
            parts.add(render(arg));
        }
        String joined = String.join(" ", parts);
        return joined.length() > MAX_ARGS ? joined.substring(0, MAX_ARGS) + "…" : joined;
    }

    private static String render(Object arg) {
        String text;
        if (arg == null) {
            text = "null";
        } else if (arg instanceof byte[] bytes) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else if (arg instanceof byte[][] arrays) {
            List<String> values = new ArrayList<>();
            for (byte[] bytes : arrays) {
                values.add(bytes == null ? "null" : new String(bytes, StandardCharsets.UTF_8));
            }
            text = String.join(" ", values);
        } else if (arg instanceof Map<?, ?> map) {
            List<String> values = new ArrayList<>();
            map.forEach((k, v) -> values.add(render(k) + "=" + render(v)));
            text = "{" + String.join(", ", values) + "}";
        } else if (arg instanceof Collection<?> collection) {
            text = String.join(" ", collection.stream().map(RecordingRedisConnections::render).toList());
        } else {
            text = String.valueOf(arg);
        }
        return text.length() > MAX_ARG ? text.substring(0, MAX_ARG) + "…" : text;
    }
}
