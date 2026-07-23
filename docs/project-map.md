# Project Map

Last reviewed against repository state: 2026-07-22
Default branch at review time: `master`

## 1. Project boundary

`config-center` is a lightweight configuration center and Feature Flag learning project.

Its current learning focus is:

- Spring Boot layered architecture
- Configuration versioning and rollback
- Conditional HTTP requests
- Long-polling notification
- Client retry, cache, fallback, and circuit breaking
- Basic authorization
- Observability and automated verification

The verified baseline is intentionally local and single-process. It is not intended to become a production-grade enterprise control plane without a separately approved expansion of scope.

## 2. Repository structure

```text
config-center/
├── pom.xml
├── README.md
├── AGENTS.md
├── examples.http
├── .env.example
├── .dockerignore
├── compose.yml
├── compose.mysql-it.yml
├── mvnw
├── mvnw.cmd
├── .mvn/
│   ├── wrapper/
│   └── docker-settings.xml
├── .github/
│   └── workflows/
│       └── ci.yml
├── docs/
│   ├── project-map.md
│   ├── dev-plan.md
│   ├── patch-log.md
│   └── config-center-persistent-deployment-plan.md
├── config-center-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/example/configcenter/
│       │   │   ├── config/
│       │   │   ├── controller/
│       │   │   ├── domain/
│       │   │   ├── dto/
│       │   │   ├── exception/
│       │   │   ├── metrics/
│       │   │   ├── repository/
│       │   │   ├── service/
│       │   │   └── web/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-local.yml
│       │       ├── application-test.yml
│       │       ├── application-mysql.yml
│       │       └── db/migration/V1__init_schema.sql
│       └── test/
│           ├── java/com/example/configcenter/MysqlPersistenceIT.java
│           └── resources/application.properties
└── config-center-client/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/example/democlient/
        │   └── resources/application.yml
        └── test/
```

## 3. Maven modules

The Maven Wrapper is the canonical build entry point. Use `./mvnw` on Unix-like systems or `./mvnw.cmd` on Windows; it pins Maven 3.9.16. Java 17 remains required.

### `config-center-server`

Responsibilities:

- Store current configurations and Feature Flags
- Maintain history snapshots
- Apply rollback
- Evaluate Feature Flags
- Produce ETag responses
- Register and notify long-polling requests
- Enforce basic write authorization
- Apply basic request rate limiting
- Expose health and metrics

Main package: `com.example.configcenter`

### `config-center-client`

Responsibilities:

- Fetch configurations over HTTP
- Reuse only structurally valid ETag and local disk cache entries
- Retry 5xx and network failures according to policy
- Reject 400/403/404 and 429 without retrying
- Fall back to a structurally valid cached configuration only after exhausted transient failures
- Run a basic availability circuit breaker that counts 5xx and network failures only
- Use a dedicated watch timeout larger than the server long-poll timeout
- Refetch and persist configurations after a watch change
- Call Feature Flag evaluation API
- Percent-encode configuration, watch, and evaluation query values through URI templates
- Reject malformed HTTP 200 envelopes before caching or acting on them

Current package: `com.example.democlient`

The package name is legacy naming. Do not rename it as incidental cleanup; handle it only in a dedicated low-risk cleanup task after behavioral stabilization.

The canonical cache file is `.config-center-client-cache.json` in the user home directory. When the canonical file is absent, the client reads `.config-center-demo-client-cache.json` once and writes the migrated data to the canonical file without deleting the legacy file. A present canonical file, including an empty or malformed one, never falls back to legacy data. Before a cache entry may supply an ETag, satisfy a 304 response, or serve transient-failure fallback, its body must be a configuration response with integral `code` equal to `0` and an array `data`; its ETag is used only when nonblank. Writes are serialized within the client instance, written to a same-directory `.tmp` file, and moved over the canonical file atomically when supported; filesystems without atomic move use a completed-temp-file replacement fallback.

## 4. Server architecture

Typical call path:

```text
HTTP request
  -> Controller
  -> Service
  -> Repository
  -> profile-selected H2 or MySQL database
```

Cross-cutting paths:

```text
Trace filter -> MDC traceId -> ApiResponse / logs
RateLimitInterceptor -> matched route pattern + bounded LRU TokenBucket map -> 429 response / instance-scoped monotonic blocked counter
GlobalExceptionHandler -> unified error body
Actuator / Micrometer -> health and metrics endpoints
```

Request boundaries mirror persistence limits: `app` is at most 100 characters, `env` 50, configuration/feature keys 200, configuration values 2000, descriptions/reasons 500, and operators 100. Expected and rollback target versions must be positive. Feature allowlists accept at most 20 non-blank entries of at most 32 characters each, keeping worst-case JSON escaping within the 4000-character column.

Validation failures, missing parameters, malformed JSON, and query type mismatches return HTTP 400 with code `4001`. Unknown exceptions are logged server-side with the request trace ID and full stack trace, while the external HTTP 500 body remains the stable, non-sensitive code `5000` / message `系统异常` envelope.

## 5. Current domain model

### Current configuration

Entity: `ConfigItem`

Business key:

```text
app + env + configKey
```

Important fields:

- `configValue`
- `description`
- `version`
- `lockVersion`
- `updatedAt`

`version` is the per-configuration business version.

`lockVersion` is the JPA optimistic-lock version.

These two concepts must remain distinct.

### Configuration history

Entity: `ConfigItemHistory`

Behavior:

- Append a snapshot after upsert
- Append a new snapshot after rollback
- Do not mutate old history records

### Configuration namespace revision

Entity: `ConfigNamespaceRevision`

Business key:

```text
app + env
```

The persistent `revision` is the monotonic watch cursor for the namespace. It advances once inside every successful configuration upsert or rollback transaction. A pessimistic database write lock serializes updates to an existing namespace row, and watchers are notified only after commit.

First-row creation is additionally protected by `NamespaceRevisionLock`, a fixed array of 64 JVM-local `ReentrantLock` stripes selected from the `(app, env)` hash. The stripe is acquired before the revision-row lookup and released from transaction `afterCompletion`, so a second local transaction cannot query before the first insert commits or rolls back. Hash collisions may serialize unrelated namespaces but the lock count stays bounded. The database `(app, env)` unique constraint remains the final data invariant; no `REQUIRES_NEW` transaction is used.

### Current Feature Flag

Entity: `FeatureFlag`

Business key:

```text
app + env + name
```

Important fields:

- `enabled`
- `rolloutPercentage`
- `allowlist`
- `version`
- `lockVersion`
- `updatedAt`

The allowlist is currently stored as JSON in one column. API validation bounds it to 20 non-blank entries of 32 characters each so even worst-case escaped content fits the 4000-character column.

### Feature history

Entity: `FeatureFlagHistory`

Behavior mirrors configuration history.

## 6. Public API map

### Configuration

- `POST /api/configs` (`X-API-Key` required)
- `GET /api/configs`
- `GET /api/configs/{key}`
- `GET /api/configs/history`
- `POST /api/configs/rollback` (`X-API-Key` required)
- `GET /api/configs/watch`

### Feature Flag

- `POST /api/features` (`X-API-Key` required)
- `GET /api/features`
- `GET /api/features/evaluate`
- `GET /api/features/history`
- `POST /api/features/rollback` (`X-API-Key` required)

### Operations

- `GET /api/ping`
- `/actuator/health`
- `/actuator/metrics`
- `/actuator/prometheus`
- `/swagger-ui/index.html`
- `/h2-console`

## 7. Important behavior paths

### Configuration upsert

```text
request
  -> API Key authorization
  -> load by app/env/key
  -> optional expectedVersion check
  -> create version 1 or increment current version
  -> save current row
  -> append history snapshot
  -> advance the persistent app/env namespace revision
  -> after transaction commit, notify watchers
```

Configuration upsert and rollback both require an API Key authorized for the requested `app` and `env`. Missing or unauthorized keys return HTTP 403 with error code `4031`.

### Configuration rollback

Intended behavior:

```text
request
  -> API Key authorization
  -> load current row
  -> load target history snapshot
  -> copy historical value into current row
  -> increment current business version
  -> append ROLLBACK history
  -> advance the persistent app/env namespace revision
  -> after transaction commit, notify watchers
```

### Write authorization scope

Configuration and Feature Flag upsert/rollback endpoints use the same lightweight API Key mapping from key to authorized `app/env`. Missing or unauthorized keys return HTTP 403/code `4031`; list, history, watch, and Feature Flag evaluation remain unauthenticated. API Key entries require non-blank key/app/env values, with app/env lengths matching request boundaries. The default development key can be replaced through `CONFIG_CENTER_API_KEY`.

### Feature evaluation

Rule order:

1. `enabled == false` -> false
2. user is in allowlist -> true
3. stable bucket is lower than rollout percentage -> true
4. otherwise -> false

### ETag fetch

```text
server loads one ordered configuration snapshot
  -> map the snapshot to response DTOs once
  -> length-prefix all response fields and hash them into a weak ETag
  -> use the same DTO snapshot for conditional comparison and the 200 body

client validates cached configuration body before use
  -> valid, nonblank ETag: GET /api/configs with If-None-Match
  -> valid body with missing/blank ETag, or invalid cache: unconditional GET
  -> 304: require and use valid cached body
  -> 200: require numeric code 0 and array data, then persist new ETag and body
  -> 400/403/404/429: fail without retry or cache fallback
  -> 5xx/network failure: retry, then use valid cache when available
```

The client circuit breaker is scoped to service availability. HTTP 4xx responses, including 429, prove that the service was reachable and therefore do not open the breaker; they still fail immediately without cache fallback. Only exhausted 5xx or network failures are cache-fallback eligible, and a request rejected by an already-open breaker is not treated as a fresh transient failure. After the open interval, one HALF_OPEN probe is admitted; success closes the breaker and any failure reopens it.

HTTP 200 responses are not trusted solely because of their status. Configuration and Feature Flag evaluation responses require an integral `code` equal to `0` and non-null `data`; configuration data must be an array. The same configuration shape is required before persisted cache data is trusted for conditional requests, 304 bodies, or fallback. Watch data must be an object with a boolean `changed` and a non-negative integral `latestVersion`. Invalid JSON, missing fields, wrong types, or nonzero codes are protocol errors and are not written to cache or treated as cache-fallback-eligible failures.

### Configuration watch

Current endpoint shape:

```text
GET /api/configs/watch
  ?app=...
  &env=...
  &sinceVersion=...
  &timeoutSeconds=...
```

The watch implementation uses the persistent `app/env` namespace revision rather than the maximum per-item configuration version.

Current behavior:

- `sinceVersion` and `latestVersion` remain the external field names for compatibility during the first fix.
- These fields represent namespace revision, not per-item version.
- Every successful configuration upsert and rollback advances the revision inside its transaction.
- Waiting clients are notified after commit with the committed revision.
- Rolled-back transactions neither expose a new revision nor notify clients.
- The controller rechecks the revision after registering a waiter so a change cannot be lost between the initial read and registration.
- Waiters are indexed by an immutable `(app, env)` key, so separator characters in either value cannot collide.
- Each waiter stores its originating request trace ID; timeout and change responses build an independent body whose `traceId` matches that request's `X-Trace-Id` response header.
- Completion removes the waiter and discards empty namespace entries from the process-local map.
- `sinceVersion` must be non-negative and `timeoutSeconds` must be within `1..60`; violations return HTTP 400.

## 8. Runtime configuration baseline

Server common defaults:

- Port `8080`
- Default profile `local` and Open Session in View disabled
- Rate limiting enabled with capacity `5`, refill rate `5` tokens per second, and at most `256` process-local buckets
- Rate-limit capacity and bucket limits must be positive; refill rate must be non-negative
- Micrometer meter `config_center_rate_limit_blocked` is a FunctionCounter; Prometheus exports it as `config_center_rate_limit_blocked_total`
- Actuator exposes health, info, metrics, and Prometheus
- One local API Key mapping: `${CONFIG_CENTER_API_KEY:kr-dev-key}` -> `demo-app/dev`

Persistence profiles:

- `local`: in-memory H2 in MySQL compatibility mode, Hibernate `ddl-auto=update`, Flyway disabled, and H2 Console enabled at `/h2-console`
- `test`: randomized in-memory H2 database, Hibernate `ddl-auto=create-drop`, Flyway disabled, and H2 Console disabled; its `application-test.yml` stays in `src/test/resources`, where server tests activate it from `application.properties`
- `mysql`: uses Connector/J, requires `CONFIG_CENTER_DB_URL`, `CONFIG_CENTER_DB_USERNAME`, and `CONFIG_CENTER_DB_PASSWORD`, applies Flyway migrations before Hibernate `ddl-auto=validate`, and disables H2 Console; missing variables fail before data-source creation and values are never logged by the validator
- `.env` is ignored. `.env.example` contains placeholders only for the Compose and manually managed MySQL paths.

MySQL schema baseline:

- `V1__init_schema.sql` is the immutable initial migration and creates `config_item`, `config_item_history`, `config_namespace_revision`, `feature_flag`, and `feature_flag_history`; Flyway owns `flyway_schema_history`.
- Primary keys use MySQL identity columns. Current-row and namespace uniqueness plus history lookup indexes mirror the JPA annotations.
- Optimistic-lock columns, string lengths/nullability, `DATETIME(6)` timestamps, 4000-character allowlist JSON columns, and `utf8mb4` are explicit.
- MySQL 8.0.46 was verified from an empty schema through migration, JPA validation, API writes/history/rollback, and a no-op second startup using a dedicated non-root application account.
- The two-service Compose runtime uses `mysql:8.4`, named volume `config-center_mysql-data`, MySQL/server healthchecks, internal-only MySQL networking, and a default server binding of `127.0.0.1:8080`. `SERVER_BIND_ADDRESS` must be explicitly changed to expose another host interface. MySQL 8.4.10 was verified through empty initialization, API write, retained-volume restart, and deleted-volume rebuild.
- The `mysql-it` Maven profile runs `MysqlPersistenceIT` through Failsafe against the dedicated `config_center_it` schema. It verifies Flyway empty/no-op migration, Hibernate validation, configuration and Feature Flag lifecycle/history/rollback, persisted namespace revision, MySQL uniqueness and optimistic locking, and `utf8mb4` data.
- `compose.mysql-it.yml` exposes only the isolated test database on loopback port `${MYSQL_IT_PORT:-33306}` when combined with `compose.yml` under project name `config-center-it`; the separate project name also isolates its volume from the persistent development runtime.
- GitHub Actions keeps the H2 `build-test` job and adds an independent MySQL 8.4 service-container job that executes the same `-Pmysql-it verify` command with per-run credentials and uploads test, application, and database logs on failure.
- Phase 9E end-to-end acceptance verified the persistent Compose runtime from an empty named volume through configuration/Feature Flag version 1–3 and rollback, server restart, full Compose restart with data retention, then `down -v` empty-volume rebuild. The reset left the local development runtime healthy with Flyway V1 and no prior acceptance data.
- The server image is built from the repository root with Maven Wrapper on Maven 3.9.16/JDK 17, then copies only the executable server JAR into a Java 17 JRE image and runs it as a non-root user.

Client defaults:

- Server base URL `http://localhost:8080`
- Standard connect/read timeouts `800 ms / 3000 ms`
- Watch timeout `10 s`, read-timeout margin `2000 ms`, and `5` rounds
- Retry policy: 3 attempts with exponential backoff and jitter
- Circuit breaker: open after 2 recorded failures for 5 seconds
- Canonical cache `${user.home}/.config-center-client-cache.json`

## 9. Known limits

The stabilization phases are complete. The remaining limits are explicit product boundaries, not claims of implemented functionality:

- Persistent MySQL startup and automated MySQL 8.4 regression are available; the CI coverage intentionally has no multi-version database matrix.
- The named Docker volume is a development persistence mechanism only; no backup, production upgrade, multi-instance coordination, or external secret management is implemented.
- The local API Key model is plaintext configuration for learning use and has no accounts, roles, JWT, or tenant model.
- Rate-limit buckets and long-poll waiters are process-local and do not coordinate across server instances.
- The client is a CLI demonstration rather than a published SDK; its package remains `com.example.democlient`.
- The JSON disk cache has no encryption or cross-process locking.
- RBAC, multi-tenancy, frontend administration, and distributed deployment are not implemented.

## 10. Documentation ownership

- `project-map.md`: current architecture and verified behavior
- `dev-plan.md`: intended changes, phases, status, and acceptance criteria
- `patch-log.md`: append-only history of completed patches
- `README.md`: verified public overview, build/run guide, and local demonstration
- `AGENTS.md`: mandatory Codex working rules

When code changes any architecture or behavior described here, update this file in the same patch.
