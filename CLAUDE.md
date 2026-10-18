# Stethoscope — notes for Claude Code

Laravel Telescope-like debugging dashboard for Spring Boot, published as a library (not an app).
Java 17 baseline (`options.release = 17`), Gradle (Groovy DSL), Spring Boot 3.5.x as compile target.

## Layout

- `stethoscope-core` — plain Java, no dependencies. `Entry`, `EntryType`, `EntryStore`/`InMemoryEntryStore`
  (ring buffer per type), `Recorder` (entry point for watchers), `Batch`/`BatchContext` (per-request grouping
  via ThreadLocal), `SqlNormalizer` (N+1), `Redactor`, `Stethoscope` (static `dump()` API).
- `stethoscope-spring-boot-autoconfigure` — all Spring code. Spring deps are `compileOnly`.
  - `StethoscopeAutoConfiguration` — core beans, gated by `stethoscope.enabled=true`
  - `web/` — `RequestWatcherFilter`, `ExceptionCapturingResolver`, MVC auto-config
  - `jdbc/` — datasource-proxy `BeanPostProcessor` + listener
  - `logging/` (Logback appender), `http/` (RestTemplate/RestClient interceptor, WebClient filter),
    `scheduling/` (observation handler for `@Scheduled`), `events/`, `cache/`, `mail/`, `dump/`,
    `jobs/` (TaskExecutor proxy; `@Async` name read from the lambda Spring submits), `models/` (Hibernate
    listeners), `security/`, `redis/` (connection factory proxy + JDK proxies on connections),
    `messaging/` (observation handler; Kafka/Rabbit adapters are only loaded when on the classpath)
  - `support/` — `Bodies` (text detection/truncation), `TypePreservingProxy` (CGLIB wrap that keeps the
    bean's concrete type; used for `CacheManager` and `JavaMailSender`)
  - `metrics/` — aggregates behind the overview (first dashboard page), kept in the app's database so
    they survive restarts. `MetricsAggregator` is an `EntryListener` folding entries into minute/hour
    counters in memory; `JdbcMetricsStore` writes them to `stethoscope_metrics` (schema in
    `META-INF/stethoscope/metrics-schema.sql`, update-then-insert instead of vendor upserts, all inside
    `Recorder.unrecorded`); `MetricsScheduler` (SmartLifecycle, private thread) flushes, samples
    CPU/heap and purges. Needs a single `DataSource`; without one the dashboard opens on Requests.
  - `ui/` — `StethoscopeUiController` + `Views` (HTML via strings, everything escaped with `Html.esc`);
    the overview is `OverviewReader` (reads) + `OverviewViews`; HTTP plumbing in `DashboardResponses`
  - assets in `src/main/resources/META-INF/stethoscope/assets/` (htmx 2.0.11, CSS)
  - registration: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `stethoscope-spring-boot-starter` — autoconfigure + datasource-proxy
- `stethoscope-bom`

## Status

- `./gradlew build` is green (Gradle 9.8.0, Spring Boot 3.5.16, datasource-proxy 1.11.0, JUnit 6.1.3 in core).
- `stethoscope.store-entries=false` (`DiscardingEntryStore`): production mode, overview metrics only,
  no bodies or query parameters captured. `stethoscope.basic-auth.*` adds HTTP Basic to the gate.
- Watchers: requests, queries, models, exceptions, logs, jobs, schedule, messages, HTTP client, events,
  cache, Redis, mail, security, dumps.
- Batches: requests, scheduled runs, jobs and received messages each open one. A job's entry lives in
  its own batch and points to the dispatching batch via `parentBatch`; the controller resolves children/parent.
- Tests: core unit tests; `StethoscopeAutoConfigurationTest` (conditions, per-watcher switches, user beans
  win, wrapping keeps concrete types); `it/StethoscopeIntegrationTest` and `it/WatchersIntegrationTest`
  (`@SpringBootTest` + MockMvc + H2 against `it/TestApplication`), `it/MoreWatchersIntegrationTest`
  (jobs, JPA models, Redis via `StubRedisConnectionFactory`, security events), `MessagingObservationHandlerTest`.
  Kafka/Rabbit auto-configs are excluded in `src/test/resources/application.properties` (no brokers).
- Integration tests share the JVM: cached contexts keep their Logback appenders attached, so never dedupe
  on shared thread-local state (see `Recorder.recordException`).

## Next steps

1. Try it in a real app (`mavenLocal()`, starter dependency, `stethoscope.enabled: true`).
2. Ideas: batch view (everything of one batch on one page), notifications, view rendering (Thymeleaf),
   Kafka batch listeners, `@Async` with custom `AsyncConfigurer` executors that are not beans.
3. Later: Jackson 3 for dumps on Boot 4 (now `toString()` without Jackson 2), WebFlux server, SSE live updates, JDBC-backed entry store, pruning,
   Maven Central publishing (`com.vanniktech.maven.publish`).

## Conventions

- Recording must never break the host app: watchers catch and swallow their own failures.
- Every value rendered into HTML goes through `Html.esc`.
- No new runtime dependencies for users. Optional integrations are `compileOnly` + `@ConditionalOnClass`.
- Every auto-configured bean is `@ConditionalOnMissingBean`.
- Controllers have no private methods: reads go to the store/a service, HTTP plumbing to a helper
  (`DashboardResponses`).
