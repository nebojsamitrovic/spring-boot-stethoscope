# Stethoscope

Debugging dashboard for Spring Boot, inspired by Laravel Telescope.
See every HTTP request with the SQL it ran (with N+1 detection), the exceptions it threw, its logs,
outgoing HTTP calls, cache hits, events and mail — plus scheduled task runs and your own dumps — in
your browser at `/stethoscope`.

- **One dependency**, zero UI build: server-rendered HTML + bundled htmx (~15 KB gzip), no CDN.
- **Off by default.** You turn it on in your dev profile.
- **Secrets masked** before storage (`Authorization`, cookies, `password`, `token`, …).

## Install

```groovy
dependencies {
    implementation platform('io.github.nebojsamitrovic:stethoscope-bom:0.1.0-SNAPSHOT')
    implementation 'io.github.nebojsamitrovic:stethoscope-spring-boot-starter'
}
```

Until it is on Maven Central: run `./gradlew publishToMavenLocal` here and add `mavenLocal()` to the
app's repositories.

```yaml
# application-dev.yml
stethoscope:
  enabled: true
```

Open `http://localhost:8080/stethoscope`.

## What it records

| Watcher     | How                                                          | Notes                                                    |
|-------------|--------------------------------------------------------------|----------------------------------------------------------|
| Requests    | Servlet filter                                               | Method, path, status, duration, headers, bodies          |
| Queries     | Wraps every `DataSource` bean with datasource-proxy          | SQL, parameters, duration; slow and N+1 tagging          |
| Exceptions  | `HandlerExceptionResolver`, filter, `ERROR` logs             | Also ones handled by `@ExceptionHandler`; deduplicated   |
| Logs        | Logback appender on the root logger                          | Level, logger, message, MDC, stack trace                 |
| HTTP Client | Interceptor on `RestTemplate`/`RestClient`, `WebClient` filter | Only clients built from Spring Boot's builder beans    |
| Schedule    | Observation handler for `@Scheduled` runs                    | Each run is its own batch, like a request                |
| Events      | `ApplicationListener`                                        | Your events only; framework events are skipped           |
| Cache       | Wraps every `CacheManager` bean                              | Hit, miss, put, evict, clear                             |
| Mail        | Wraps every `JavaMailSender` bean                            | Recipients, subject, bodies, attachments, HTML preview   |
| Dumps       | `Stethoscope.dump(value)`                                    | Pretty JSON via Jackson when available                   |

Everything recorded during one request (or one scheduled run) is linked: the request page lists its
queries, exceptions, logs, HTTP calls, cache operations, events, mail and dumps.

Wrapped beans (`CacheManager`, `JavaMailSender`) are class-based proxies, so injecting the concrete
type (`CaffeineCacheManager`, `JavaMailSenderImpl`) keeps working.

### Dumps

```java
import io.github.nebojsamitrovic.stethoscope.core.Stethoscope;

Stethoscope.dump(order);                      // shows up under "Dumps", linked to the current request
var total = Stethoscope.dump(cart.total());   // returns its argument
```

When Stethoscope is disabled, `dump` does nothing.

## Configuration

```yaml
stethoscope:
  enabled: false                 # master switch
  path: /stethoscope
  max-entries: 1000              # in-memory ring buffer, per entry type
  allowed-ips: [127.0.0.1, "::1", "0:0:0:0:0:0:0:1"]   # empty list = everyone
  redact-headers: [authorization, cookie, set-cookie, ...]
  redact-parameters: [password, token, secret, ...]
  requests:
    enabled: true
    ignore-paths: ["/actuator/**", "/**/*.css", ...]
    record-request-body: true
    record-response-body: false  # buffers responses; keep off for streaming/SSE
    max-body-size: 65536        
    slow-threshold: 1s
  queries:
    enabled: true
    record-parameters: true
    slow-threshold: 100ms
    duplicate-threshold: 5       # same statement N times in one request → N+1
  exceptions:
    enabled: true
    max-stack-trace-frames: 40
  logs:
    enabled: true
    level: INFO
    ignore-loggers: []           # logger name prefixes
    record-exceptions: true      # exceptions in ERROR logs also go to "Exceptions"
  http-client:
    enabled: true
    record-bodies: true          # read-ahead of at most max-body-size, never buffers whole responses
    max-body-size: 65536        
    slow-threshold: 1s
  scheduled:
    enabled: true
  events:
    enabled: true
    ignore-packages: ["org.springframework.", ...]
  cache:
    enabled: true
  mail:
    enabled: true
```

## Access control

By default only localhost may open the dashboard. Plug in your own rule:

```java
@Bean
StethoscopeGate stethoscopeGate() {
    return request -> request.isUserInRole("ADMIN");
}
```

With Spring Security, also permit the path (the gate runs before Security's filter chain):

```java
http.authorizeHttpRequests(a -> a.requestMatchers("/stethoscope/**").permitAll() /* gate decides */);
```

## Modules

| Module                                   | Contents                                                     |
|------------------------------------------|--------------------------------------------------------------|
| `stethoscope-core`                       | Entry model, ring-buffer store, recorder (no Spring)         |
| `stethoscope-spring-boot-autoconfigure`  | Watchers, properties, htmx UI                                |
| `stethoscope-spring-boot-starter`        | The one dependency users add                                 |
| `stethoscope-bom`                        | Version alignment                                            |

## Known limitations

- Servlet stack only (no WebFlux server yet; `WebClient` calls are recorded).
- Work on other threads (`@Async`, executors) is recorded but not linked to the request.
- Logs are recorded with Logback only (Spring Boot's default).
- After wrapping, the `DataSource` bean is a `ProxyDataSource`. Inject `DataSource`, not
  `HikariDataSource`, or set `stethoscope.queries.enabled=false`.

## License

Apache 2.0. Bundles [htmx](https://htmx.org) (Zero-Clause BSD).
