# Stethoscope

Request-level debugging dashboard for Spring Boot, inspired by Laravel Telescope.
See every HTTP request, the SQL it ran (with N+1 detection) and the exceptions it threw — in your
browser at `/stethoscope`.

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

| Watcher    | How                                                        | Notes                                              |
|------------|------------------------------------------------------------|----------------------------------------------------|
| Requests   | Servlet filter                                             | Method, path, status, duration, headers, bodies   |
| Queries    | Wraps every `DataSource` bean with datasource-proxy        | SQL, parameters, duration; slow and N+1 tagging   |
| Exceptions | `HandlerExceptionResolver` + filter                        | Also catches ones handled by `@ExceptionHandler`  |

Everything recorded during one request is linked, so a request page shows its queries and exceptions.

## Configuration

```yaml
stethoscope:
  enabled: false                 # master switch
  path: /stethoscope
  max-entries: 1000              # in-memory ring buffer
  allowed-ips: [127.0.0.1, "::1", "0:0:0:0:0:0:0:1"]   # empty list = everyone
  redact-headers: [authorization, cookie, set-cookie, ...]
  redact-parameters: [password, token, secret, ...]
  requests:
    enabled: true
    ignore-paths: ["/actuator/**", "/**/*.css", ...]
    record-request-body: true
    record-response-body: false  # buffers responses; keep off for streaming/SSE
    max-body-size: 64KB
    slow-threshold: 1s
  queries:
    enabled: true
    record-parameters: true
    slow-threshold: 100ms
    duplicate-threshold: 5       # same statement N times in one request → N+1
  exceptions:
    enabled: true
    max-stack-trace-frames: 40
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

- Servlet stack only (no WebFlux yet).
- Work on other threads (`@Async`, executors) is recorded but not linked to the request.
- After wrapping, the `DataSource` bean is a `ProxyDataSource`. Inject `DataSource`, not
  `HikariDataSource`, or set `stethoscope.queries.enabled=false`.

## License

Apache 2.0. Bundles [htmx](https://htmx.org) (Zero-Clause BSD).
