# Stethoscope

Debugging and monitoring dashboard for Spring Boot, inspired by Laravel
[Telescope](https://laravel.com/docs/telescope) and [Pulse](https://laravel.com/docs/pulse) — both in
one library.

- **Like Telescope** (development): every request, query, job, log, exception, … recorded in full,
  grouped per request, in memory.
- **Like Pulse** (production): an overview of throughput, slow routes, slow queries, exceptions, jobs
  and server load over the last hour to 7 days, kept as aggregates in your own database
  (`store-entries: false` turns the Telescope part off). See [Overview](#overview).

See every HTTP request with the SQL it ran (with N+1 detection), the entities it changed, the
exceptions it threw, its logs, background jobs, outgoing HTTP calls, Redis commands, cache hits,
events, messages, mail and security decisions — plus scheduled task runs, Kafka/RabbitMQ listeners
and your own dumps — in your browser at `/stethoscope`.

- **One dependency**, zero UI build: server-rendered HTML + bundled htmx (~15 KB gzip), no CDN.
- **Off by default.** You turn it on per profile (full recording in dev, overview only in production).
- **Secrets masked** before storage (`Authorization`, cookies, `password`, `token`, …).

## Install

Requirements: Java 17+, Spring Boot 3.x or 4.x with Spring MVC (servlet stack).

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nebojsamitrovic/stethoscope-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.nebojsamitrovic/stethoscope-spring-boot-starter)

### 1. Add the dependency to your app

Gradle:

```groovy
dependencies {
    implementation 'io.github.nebojsamitrovic:stethoscope-spring-boot-starter:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.nebojsamitrovic</groupId>
    <artifactId>stethoscope-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

With several Stethoscope modules, the BOM keeps their versions aligned:
`implementation platform('io.github.nebojsamitrovic:stethoscope-bom:0.1.0')`.

### 2. Turn it on in your dev profile

It is off by default. In `src/main/resources/application-dev.yml`:

```yaml
stethoscope:
  enabled: true
```

Run the app with that profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
# or: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Open `http://localhost:8080/stethoscope`. With Spring Security, see [Access control](#access-control).

Do not enable it in production: it keeps requests, SQL, payloads and mail in memory. If it is enabled
with a `prod`, `production` or `live` profile, the app logs a loud warning at startup.

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
| Jobs        | Wraps every `TaskExecutor` bean                              | `@Async` methods by name; own batch, linked to the request that dispatched them |
| Models      | Hibernate post-insert/update/delete listeners                | Changed attributes (`old → new`); secrets masked; lazy associations never loaded |
| Security    | Spring Security application events                           | Logins, failed logins, logouts, denied (and published granted) authorizations |
| Messages    | spring-kafka / spring-rabbit observations                    | Sent and received; each listener invocation is its own batch |
| Redis       | Wraps every `RedisConnectionFactory` bean                    | Command, arguments, duration                             |

Everything recorded during one unit of work — a request, a scheduled run, a job or a received
message — is linked: its page lists the queries, model changes, exceptions, logs, HTTP calls, Redis
commands, cache operations, events, messages, mail and dumps it caused, and a request page also lists
the `@Async` jobs it dispatched.

Wrapped beans (`CacheManager`, `JavaMailSender`, `TaskExecutor`, `RedisConnectionFactory`) are
class-based proxies, so injecting the concrete type (`CaffeineCacheManager`, `JavaMailSenderImpl`,
`ThreadPoolTaskExecutor`, `LettuceConnectionFactory`) keeps working.

Kafka and RabbitMQ are recorded through Spring's observation support, which Stethoscope switches on
for Spring Boot's listener container factories and templates. Without Spring Boot Actuator there is
no `ObservationRegistry`, so Stethoscope provides one when Kafka or RabbitMQ is on the classpath.

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
  store-entries: true            # false: keep no entries, capture no bodies (overview only)
  allowed-ips: [127.0.0.1, "::1", "0:0:0:0:0:0:0:1"]   # empty list = everyone
  basic-auth:                    # optional, checked in addition to allowed-ips
    username:
    password:
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
  jobs:
    enabled: true
  models:
    enabled: true
  security:
    enabled: true
  messages:
    enabled: true
  redis:
    enabled: true
```

## Overview

This is the Pulse part. The dashboard opens on an overview of what the application did over the last hour, 6 hours,
24 hours or 7 days. Entries live in memory and are gone after a restart; the overview's metrics
are kept in the application's own database instead, so a week of traffic survives any number of
deploys:

- requests per minute by status class, average and max duration
- slow requests by route pattern (`GET /orders/{id}`, never the raw URI)
- slow queries by statement shape, exceptions by class and location (with the latest message)
- jobs and scheduled tasks (slowest first) and their failures, slow outgoing HTTP calls by origin
- CPU and heap of every instance, once a minute

Counters are collected in memory and written every 10 s in one transaction, to minute buckets
(kept 25 h) and hour buckets (kept `retention`). Their number depends on distinct routes and
statements, not on traffic. "Slow" uses the thresholds above (`requests.slow-threshold`, ...).
It needs a single `DataSource`; without one the dashboard opens on Requests as before.

```yaml
# production
stethoscope:
  enabled: true
  store-entries: false           # overview only: no bodies, no parameters, no entries in memory
  allowed-ips: []                # behind a proxy every client has the proxy's address...
  basic-auth:                    # ...so ask for credentials instead (HTTPS only)
    username: ops
    password: ${STETHOSCOPE_PASSWORD}
  metrics:
    enabled: true
    retention: 7d                # hour buckets; minute buckets are kept 25 h
    flush-interval: 10s
    initialize-schema: true      # CREATE TABLE IF NOT EXISTS at startup
    system: true                 # CPU and heap per instance
    instance-name:               # defaults to the host name
```

The table is `stethoscope_metrics`; with Flyway or Liquibase copy
`META-INF/stethoscope/metrics-schema.sql` from the jar into a migration and set
`initialize-schema: false`. Tested on PostgreSQL and H2. The metrics' own SQL is never recorded.

## Access control

By default only localhost may open the dashboard. `stethoscope.basic-auth.*` adds HTTP Basic
credentials on top of `allowed-ips`, which works whatever the application's own authentication is.
Or plug in your own rule. The gate runs before Spring Security's filter chain, so there is no
authenticated user yet (`request.isUserInRole(..)` is always false); check what the request
itself carries:

```java
@Bean
StethoscopeGate stethoscopeGate() {
    return request -> "10.0.0.5".equals(request.getRemoteAddr());
}
```

With Spring Security, also permit the path:

```java
http.authorizeHttpRequests(a -> a.requestMatchers("/stethoscope/**").permitAll() /* gate decides */);
```

## Modules

| Module                                   | Contents                                                     |
|------------------------------------------|--------------------------------------------------------------|
| `stethoscope-core`                       | Entry model, ring-buffer store, recorder (no Spring)         |
| `stethoscope-spring-boot-autoconfigure`  | Watchers, overview metrics, properties, htmx UI              |
| `stethoscope-spring-boot-starter`        | The one dependency users add                                 |
| `stethoscope-bom`                        | Version alignment                                            |

## Known limitations

- Servlet stack only (no WebFlux server yet; `WebClient` calls are recorded).
- Jobs are recorded for Spring `TaskExecutor` beans only; plain `ExecutorService`s and
  `CompletableFuture.supplyAsync(...)` without an executor are not linked to the request.
- Kafka batch listeners are not observed by spring-kafka, so they are not recorded.
- Logs are recorded with Logback only (Spring Boot's default).
- After wrapping, the `DataSource` bean is a `ProxyDataSource`. Inject `DataSource`, not
  `HikariDataSource`, or set `stethoscope.queries.enabled=false`.

## License

Apache 2.0. Bundles [htmx](https://htmx.org) (Zero-Clause BSD).
