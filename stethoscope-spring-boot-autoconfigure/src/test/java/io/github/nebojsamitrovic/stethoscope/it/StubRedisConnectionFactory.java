package io.github.nebojsamitrovic.stethoscope.it;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisStringCommands;

/** In-memory stand-in for a Redis server that understands GET and SET. */
class StubRedisConnectionFactory implements RedisConnectionFactory {

    private final Map<String, byte[]> values = new ConcurrentHashMap<>();

    @Override
    public RedisConnection getConnection() {
        RedisStringCommands strings = (RedisStringCommands) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RedisStringCommands.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put(key(args[0]), (byte[]) args[1]);
                        yield Boolean.TRUE;
                    }
                    case "get" -> values.get(key(args[0]));
                    default -> defaultValue(method.getReturnType());
                });
        InvocationHandler connection = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return switch (method.getName()) {
                case "stringCommands" -> strings;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "StubRedisConnection";
                default -> defaultValue(method.getReturnType());
            };
        };
        return (RedisConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RedisConnection.class}, connection);
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return false;
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return null;
    }

    private static String key(Object raw) {
        return new String((byte[]) raw, StandardCharsets.UTF_8);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class) {
            return type == int.class ? (Object) 0 : (Object) 0L;
        }
        return null;
    }
}
