# Project Map

Last reviewed against repository state: 2026-07-14
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

It is not intended to become a production-grade enterprise control plane during the current stabilization phase.

## 2. Repository structure

```text
config-center/
├── pom.xml
├── README.md
├── AGENTS.md
├── examples.http
├── .github/
│   └── workflows/
│       └── ci.yml
├── docs/
│   ├── project-map.md
│   ├── dev-plan.md
│   └── patch-log.md
├── config-center-server/
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
│       │   └── resources/application.yml
│       └── test/
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
- Reuse ETag and local disk cache
- Retry selected transient failures
- Fall back to cached configuration
- Run a basic circuit breaker
- Watch for configuration changes
- Call Feature Flag evaluation API

Current package: `com.example.democlient`

The package name is legacy naming. Do not rename it as incidental cleanup; handle it only in a dedicated low-risk cleanup task after behavioral stabilization.

## 4. Server architecture

Typical call path:

```text
HTTP request
  -> Controller
  -> Service
  -> Repository
  -> H2 database
```

Cross-cutting paths:

```text
Trace filter -> MDC traceId -> ApiResponse / logs
RateLimitInterceptor -> TokenBucket -> 429 response
GlobalExceptionHandler -> unified error body
Actuator / Micrometer -> health and metrics endpoints
```

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

The persistent `revision` is the monotonic watch cursor for the namespace. It advances once inside every successful configuration upsert or rollback transaction. A pessimistic write lock serializes updates to an existing namespace row, and watchers are notified only after commit.

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

The allowlist is currently stored as JSON in one column. Keep this design during stabilization.

### Feature history

Entity: `FeatureFlagHistory`

Behavior mirrors configuration history.

## 6. Public API map

### Configuration

- `POST /api/configs`
- `GET /api/configs`
- `GET /api/configs/{key}`
- `GET /api/configs/history`
- `POST /api/configs/rollback`
- `GET /api/configs/watch`

### Feature Flag

- `POST /api/features`
- `GET /api/features`
- `GET /api/features/evaluate`
- `GET /api/features/history`
- `POST /api/features/rollback`

### Operations

- `/actuator/health`
- `/actuator/metrics`
- `/actuator/prometheus`
- Swagger UI
- H2 Console

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
  -> after transaction commit, notify watchers
```

The notification portion must be corrected during the watch stabilization phase.

### Write authorization scope

During Phase 1, API Key authorization applies only to configuration upsert and rollback. Feature Flag write endpoints deliberately remain outside this minimal authorization scope; expanding them requires a separate security decision and patch.

### Feature evaluation

Rule order:

1. `enabled == false` -> false
2. user is in allowlist -> true
3. stable bucket is lower than rollout percentage -> true
4. otherwise -> false

### ETag fetch

```text
client reads cached ETag
  -> GET /api/configs with If-None-Match
  -> 304: use cached body
  -> 200: persist new ETag and body
  -> transient failure: use cache when available
```

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

## 8. Known stabilization issues

The canonical task list and status live in `docs/dev-plan.md`.

Current high-priority categories:

- Error body and HTTP status inconsistency
- Incomplete authorization coverage
- Client long-poll timeout mismatch
- Client does not actually refetch after watch change
- Client accepts non-success responses too loosely
- Insufficient regression tests
- Documentation drift
- Legacy package and cache-file naming cleanup

## 9. Documentation ownership

- `project-map.md`: current architecture and verified behavior
- `dev-plan.md`: intended changes, phases, status, and acceptance criteria
- `patch-log.md`: append-only history of completed patches
- `README.md`: minimal public overview during stabilization
- `AGENTS.md`: mandatory Codex working rules

When code changes any architecture or behavior described here, update this file in the same patch.
