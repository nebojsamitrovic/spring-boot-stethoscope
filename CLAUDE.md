# Stethoscope — notes for Claude Code

Laravel Telescope-like debugging dashboard for Spring Boot, published as a library (not an app).
Java 17 baseline (`options.release = 17`), Gradle (Groovy DSL), Spring Boot 3.5.x as compile target.

## Layout

- `stethoscope-core` — plain Java, no dependencies. `Entry`, `EntryStore`/`InMemoryEntryStore`,
  `Recorder` (entry point for watchers), `Batch`/`BatchContext` (per-request grouping via ThreadLocal),
  `SqlNormalizer` (N+1), `Redactor`.
- `stethoscope-spring-boot-autoconfigure` — all Spring code. Spring deps are `compileOnly`.
  - `StethoscopeAutoConfiguration` — core beans, gated by `stethoscope.enabled=true`
  - `web/` — `RequestWatcherFilter`, `ExceptionCapturingResolver`, MVC auto-config
  - `jdbc/` — datasource-proxy `BeanPostProcessor` + listener
  - `ui/` — `StethoscopeUiController` + `Views` (HTML via strings, everything escaped with `Html.esc`)
  - assets in `src/main/resources/META-INF/stethoscope/assets/` (htmx 2.0.11, CSS)
  - registration: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `stethoscope-spring-boot-starter` — autoconfigure + datasource-proxy
- `stethoscope-bom`

## Status

- `./gradlew build` is green (Gradle 9.8.0, Spring Boot 3.5.16, datasource-proxy 1.11.0, JUnit 6.1.3 in core).
- Tests: core unit tests; `StethoscopeAutoConfigurationTest` (conditions, user beans win, DataSource
  wrapping); `it/StethoscopeIntegrationTest` (`@SpringBootTest` + MockMvc + H2: batch linking, N+1 tag,
  handled/unhandled exceptions, redaction, dashboard 403 for non-allowed IP).
- `publishToMavenLocal` works; group is `io.github.nebojsamitrovic`.

## Next steps

1. Try it in a real app (`mavenLocal()`, starter dependency, `stethoscope.enabled: true`).
2. Later: Spring Boot 4 compatibility check, WebFlux, outgoing HTTP watcher, log watcher,
   SSE live updates, JDBC-backed store, Maven Central publishing (`com.vanniktech.maven.publish`).

## Conventions

- Recording must never break the host app: watchers catch and swallow their own failures.
- Every value rendered into HTML goes through `Html.esc`.
- No new runtime dependencies for users. Optional integrations are `compileOnly` + `@ConditionalOnClass`.
- Every auto-configured bean is `@ConditionalOnMissingBean`.
