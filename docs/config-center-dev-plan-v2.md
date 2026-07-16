# Development Plan

Last updated: 2026-07-16

## 1. Objective

Continue improving `config-center` from the verified Phase 0–5 baseline.

The project is already runnable, tested, and documented. The next goal is to close the remaining correctness, concurrency, observability, client reliability, validation, and resource-management gaps before adding new capabilities.

Priority order:

1. Correctness defects
2. Concurrency and transaction safety
3. API and protocol consistency
4. Client failure semantics
5. Resource lifecycle and operational safety
6. Minimal security consistency
7. New features

Do not add new product capabilities while Phase 6 contains open P1 issues.

## 2. Global constraints

- Preserve Java 17.
- Preserve Spring Boot 3.
- Preserve the Maven multi-module structure.
- Keep H2 as the local baseline during Phases 6–8.
- Do not introduce Redis, message queues, distributed locks, WebSocket, SSE, RBAC, JWT, multi-tenancy, Kubernetes, or a frontend.
- Keep every Codex task limited to one sub-phase or one coherent defect.
- Preserve existing API paths and JSON field names unless a phase explicitly permits a compatibility correction.
- Add regression tests for every behavior change.
- Run the smallest relevant test set first, then full verification.
- Every code patch must update:
  - `docs/dev-plan.md`
  - `docs/patch-log.md`
- Update `docs/project-map.md` whenever architecture, concurrency, persistence, watch, client, API, or runtime behavior changes.
- Update `README.md` only when verified public behavior, commands, authorization, or known limits change.
- Use `frugal-dev-runner`.
- Do not expand scope.

## 3. Status legend

- `[ ]` Not started
- `[-]` In progress
- `[x]` Verified complete
- `[!]` Blocked or requires a decision

A task is not complete until verification evidence is recorded.

## 4. Verified baseline

### Phase 0 — Repository governance and documentation reset `[x]`

- Added `AGENTS.md` and project docs.
- Added Maven Wrapper.
- Established full Maven verification.

### Phase 1 — API correctness and configuration write authorization `[x]`

- Corrected HTTP status mapping.
- Added authorization error code.
- Applied API Key authorization to configuration upsert and rollback.
- Added controller regression tests.

### Phase 2 — Configuration watch revision correctness `[x]`

- Added persistent `app/env` namespace revision.
- Removed maximum-item-version watch semantics.
- Added after-commit watcher notification.
- Added rollback and multi-key watch tests.

### Phase 3 — Client reliability path closure `[x]`

- Split standard and watch HTTP clients.
- Added deterministic retry and fallback semantics.
- Added actual refetch after watch change.
- Added cache migration and client tests.

### Phase 4 — Server regression coverage and bounded cleanup `[x]`

- Added configuration, Feature Flag, ETag, rate-limit, and optimistic-lock tests.
- Reviewed transaction boundaries.
- Confirmed JaCoCo reports.

### Phase 5 — Documentation rebuild and verified presentation `[x]`

- Rebuilt README around verified behavior.
- Added verified local demonstration.
- Synchronized README, project map, examples, and patch log.
- Documented remaining limits.

---

# Phase 6 — Remaining correctness closure

Goal: eliminate the remaining P1 correctness and concurrency defects before new capabilities are considered.

Phase 6 must be completed before Phase 8 security expansion or any new feature work.

## Phase 6A — ETag snapshot correctness `[x]`

### Goal

Guarantee that each configuration-list ETag represents the exact response body returned by the same request, and prevent stale cache reuse after an H2 reset.

### Current defects

1. ETag currently hashes only ordered `configKey:version` values.
2. H2 can restart business versions from 1 while the client disk cache survives.
3. Different values with reused keys and versions can reuse an old ETag.
4. The controller currently generates ETag and body through separate repository queries.
5. A concurrent write between the two queries can produce an old ETag with a new body or a false HTTP 304.

### Required design

- Load the ordered configuration list once.
- Generate the API response data and ETag from that same in-memory snapshot.
- Include all representation-relevant fields:
  - key
  - value
  - description
  - business version
- Use an unambiguous serialization before hashing.
- Preserve weak ETag format unless a strong ETag is explicitly justified.
- Preserve API path and JSON field names.
- Optionally add `Cache-Control: no-cache`.

### Tasks

- [x] Introduce a service result representing one list snapshot and its ETag.
- [x] Remove the controller's separate `etagForList()` and `list()` query sequence.
- [x] Change ETag input so value and description changes affect the tag.
- [x] Add a reset-style version-reuse regression test.
- [x] Add a regression test proving ETag and body use the same loaded snapshot.
- [x] Preserve matching-ETag HTTP 304 behavior.
- [x] Remove the corresponding README known-limit entry after verification.
- [x] Update project map and patch log.

### Acceptance criteria

- Different configuration representations cannot reuse an ETag merely because business versions were reused.
- ETag and body always describe the same snapshot.
- Unchanged data still returns HTTP 304 without a JSON body.
- API paths and JSON fields remain unchanged.
- Focused tests and full Maven verification pass.

### Likely files

- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigService.java`
- `config-center-server/src/main/java/com/example/configcenter/service/EtagUtil.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigEtagIntegrationTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/patch-log.md`

### Compatibility

- API path impact: none
- JSON impact: none
- Data impact: none
- Cache impact: old ETags naturally become invalid

### Verification

```bash
./mvnw -q -B -pl config-center-server -Dtest=ConfigEtagIntegrationTest test
./mvnw -q -B clean verify
git diff --check
```

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

## Phase 6B — Watch async correctness and waiter lifecycle `[ ]`

### Goal

Make long-poll responses preserve each watch request's own trace identity and prevent waiter-key collision or empty-list accumulation.

### Current defects

1. `ApiResponse.ok()` reads traceId from the current thread MDC.
2. Watch timeout callbacks run after the original request thread has finished.
3. Watch change notifications run on the configuration-write thread.
4. Watch response body traceId can be null, the write request's traceId, or different from the response header.
5. Waiter map keys use `app + "|" + env`, which can collide.
6. Empty namespace lists can remain in the waiter map.
7. `sinceVersion` and `timeoutSeconds` lack sufficient validation.

### Required design

- Capture each watch request's traceId when registering.
- Store a typed waiter record containing the namespace key, request traceId, and `DeferredResult`.
- Add an explicit `ApiResponse` factory that accepts a traceId.
- Create one response per waiter using its own traceId.
- Replace string-composed keys with a typed immutable namespace key.
- Remove empty waiter lists.
- Validate:
  - `sinceVersion >= 0`
  - `timeoutSeconds` within `1..60`

### Tasks

- [ ] Add typed `NamespaceKey`.
- [ ] Add typed waiter holder.
- [ ] Capture watch request traceId at registration.
- [ ] Add explicit-trace response creation.
- [ ] Ensure timeout body/header traceIds match.
- [ ] Ensure notification body/header traceIds match.
- [ ] Ensure two watchers retain their own distinct traceIds.
- [ ] Remove empty namespace entries.
- [ ] Add watch query validation.
- [ ] Add separator-character collision tests.
- [ ] Add invalid revision and timeout tests.
- [ ] Update project map and patch log.

### Acceptance criteria

- Watch response header and body contain the same traceId.
- A write request's traceId never leaks into watch responses.
- Distinct app/env pairs cannot collide.
- Completed namespaces do not leave permanent empty lists.
- Invalid parameters return HTTP 400.
- Existing watch path and response fields remain unchanged.

### Likely files

- `config-center-server/src/main/java/com/example/configcenter/config/TraceIdFilter.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/ApiResponse.java`
- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigWatchNotifier.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigWatchIntegrationTest.java`
- project docs

### Compatibility

- API path impact: none
- JSON field impact: none
- Invalid watch parameters now return HTTP 400
- Data impact: none

### Verification

```bash
./mvnw -q -B -pl config-center-server -Dtest=ConfigWatchIntegrationTest test
./mvnw -q -B clean verify
git diff --check
```

### Recommended Codex configuration

- Model: GPT-5.6 Sol
- Reasoning: High

---

## Phase 6C — Client circuit-breaker semantics `[ ]`

### Goal

Ensure caller errors do not contaminate service-availability state or cause stale-cache fallback.

### Current defects

- `ReliableHttp` records all non-200/non-304 responses as breaker failures before classification.
- Repeated 400/403/404 responses can open the breaker.
- A later `CIRCUIT_OPEN` result can become cache-fallback eligible.
- Existing tests do not verify OPEN and HALF_OPEN transitions.

### Required failure classification

- 200 / 304:
  - record success
  - return
- 400 / 401 / 403 / 404 and other caller errors:
  - no retry
  - no cache fallback
  - do not count as breaker availability failures
- 429:
  - no immediate retry
  - no cache fallback
  - recommended: do not open the availability breaker
- 5xx:
  - count as availability failure
  - retry
  - allow fallback after exhaustion
- Network failures:
  - count as availability failure
  - retry
  - allow fallback after exhaustion
- Unexpected errors:
  - must not leave HALF_OPEN permanently occupied

### Tasks

- [ ] Move failure recording into transient-failure branches.
- [ ] Define and document 429 breaker semantics.
- [ ] Add direct `CircuitBreakerTest`.
- [ ] Test CLOSED -> OPEN.
- [ ] Test OPEN -> HALF_OPEN.
- [ ] Test HALF_OPEN success -> CLOSED.
- [ ] Test HALF_OPEN failure -> OPEN.
- [ ] Test repeated 403/404 never opens the breaker.
- [ ] Test repeated 429 never becomes stale-cache success.
- [ ] Test fallback occurs only after transient failures.
- [ ] Add a controllable clock if needed.
- [ ] Update project map and patch log.

### Acceptance criteria

- Caller errors never open the availability breaker.
- 403/404 cannot indirectly trigger fallback.
- State transitions are deterministically tested.
- HALF_OPEN cannot remain permanently blocked.
- Existing 5xx and network retry behavior remains intact.

### Likely files

- `config-center-client/src/main/java/com/example/democlient/ReliableHttp.java`
- `config-center-client/src/main/java/com/example/democlient/CircuitBreaker.java`
- `config-center-client/src/main/java/com/example/democlient/HttpRequestFailedException.java`
- client tests
- project docs

### Compatibility

- Server API impact: none
- Client behavior correction: fewer false circuit-open events and stale fallbacks
- Cache format impact: none

### Verification

```bash
./mvnw -q -B -pl config-center-client test
./mvnw -q -B clean verify
git diff --check
```

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

## Phase 6D — Namespace first-write concurrency `[ ]`

### Goal

Guarantee that concurrent first writes to the same `app/env` namespace both succeed and produce a monotonic namespace revision.

### Current defect

`PESSIMISTIC_WRITE` works only when the namespace revision row already exists. Two concurrent first writes can both attempt to insert the same namespace row, causing one valid transaction to fail with a unique-key conflict.

### Required invariants

- Current config, history, namespace revision, and notification remain one atomic logical write.
- Notification occurs only after commit.
- Two concurrent first writes to the same app/env and different keys both succeed.
- Final revision equals committed write count.
- Do not use `REQUIRES_NEW`.
- Do not add Redis, distributed locks, or database-specific advisory locks.

### Preferred implementation direction

For the current single-process baseline:

- Use a bounded or striped in-process namespace lock.
- Acquire it before first-row lookup/create.
- Preserve the database pessimistic lock for existing rows.
- Avoid an unbounded `Map<NamespaceKey, Lock>`.
- Preserve the unique constraint as the final invariant.

### Tasks

- [ ] Add a deterministic concurrent first-write integration test.
- [ ] Verify both writes succeed.
- [ ] Verify two current rows and two history rows exist.
- [ ] Verify namespace revision equals 2.
- [ ] Verify watchers observe the committed revision.
- [ ] Implement bounded/striped first-creation serialization.
- [ ] Reverify rollback behavior.
- [ ] Document the exact locking model.
- [ ] Update patch log.

### Acceptance criteria

- Concurrent first writes do not fail with unique-key conflict.
- No lost revision increment.
- No revision is visible before commit.
- Rollback does not advance revision or notify.
- No unbounded lock registry is added.
- Full verification passes.

### Likely files

- `ConfigNamespaceRevisionService.java`
- `ConfigNamespaceRevisionRepository.java`
- a small bounded lock component if needed
- watch/concurrency integration tests
- project docs

### Compatibility

- API impact: none
- Data schema impact: preferably none
- Concurrency behavior becomes more reliable

### Verification

```bash
./mvnw -q -B -pl config-center-server -Dtest=ConfigWatchIntegrationTest test
./mvnw -q -B clean verify
git diff --check
```

### Recommended Codex configuration

- Model: GPT-5.6 Sol
- Reasoning: Extra High

---

# Phase 7 — API, protocol, cache, and resource hardening

Goal: improve boundary validation and local operational safety after Phase 6.

## Phase 7A — Request validation and exception hygiene `[ ]`

### Tasks

- [ ] Add `@Size` constraints matching entity limits.
- [ ] Add `@Positive` for target and expected versions where appropriate.
- [ ] Validate allowlist count, blank items, and item length.
- [ ] Handle `MethodArgumentTypeMismatchException`.
- [ ] Log unknown exceptions with traceId and full server-side stack trace.
- [ ] Keep external system-error responses stable and non-sensitive.
- [ ] Add controller tests.

### Acceptance criteria

- Oversized values are rejected as HTTP 400 before persistence.
- Invalid numeric parameters return HTTP 400, not 500.
- Unknown exceptions are logged with traceId.
- External responses expose no stack trace.

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

## Phase 7B — Rate limiter lifecycle and metrics semantics `[ ]`

### Tasks

- [ ] Use matched route patterns instead of concrete dynamic paths where possible.
- [ ] Prevent one permanent bucket per concrete configuration key.
- [ ] Add bounded capacity or idle expiration.
- [ ] Validate rate-limit configuration.
- [ ] Replace Gauge `_total` semantics with Counter or FunctionCounter.
- [ ] Add lifecycle and metric tests.
- [ ] Update metrics documentation if the name changes.

### Acceptance criteria

- Bucket count cannot grow without bound.
- Dynamic keys share the intended route bucket.
- Blocked count behaves as a monotonic counter.
- No external gateway or distributed limiter is introduced.

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

## Phase 7C — Client protocol validation and atomic cache writes `[ ]`

### Tasks

- [ ] Build URLs with `UriComponentsBuilder` or equivalent encoding.
- [ ] Validate `code == 0` and required `data`.
- [ ] Validate watch response fields and types.
- [ ] Treat malformed HTTP 200 responses as protocol errors.
- [ ] Write cache through a temporary file.
- [ ] Replace the canonical cache with atomic move when supported.
- [ ] Fall back to safe replace when atomic move is unavailable.
- [ ] Synchronize in-process file writes.
- [ ] Add special-character, malformed-response, and failed-replacement tests.
- [ ] Optionally record cache timestamp for diagnostics.

### Acceptance criteria

- Special characters do not corrupt request URLs.
- Missing fields cannot silently become `changed=false`.
- Cache files are not partially overwritten during normal interruption.
- Existing cache remains readable or migration is documented.

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

# Phase 8 — Minimal security consistency

Goal: apply the existing lightweight app/env API Key model consistently to all control-plane writes.

Do not expand this phase into RBAC, JWT, account management, or multi-tenancy.

## Phase 8A — Feature Flag write authorization `[ ]`

### Target behavior

Require `X-API-Key` for:

- `POST /api/features`
- `POST /api/features/rollback`

Keep Feature Flag reads and evaluation unauthenticated.

### Tasks

- [ ] Reuse `ApiKeyService` in Feature Flag write endpoints.
- [ ] Add allowed, missing, and unauthorized tests.
- [ ] Update `examples.http`.
- [ ] Update README API table and demonstration.
- [ ] Update project map and patch log.
- [ ] Allow the development key to be overridden through external configuration or environment variables.
- [ ] Validate API-key configuration entries.

### Acceptance criteria

- All configuration and Feature Flag writes use the same app/env authorization model.
- Read/evaluate endpoints remain unchanged.
- Unauthorized writes return HTTP 403 and code `4031`.
- No RBAC, JWT, user table, or tenant model is introduced.
- Full verification passes.

### Compatibility

- Feature Flag write callers must now provide `X-API-Key`.
- Read and evaluate APIs remain compatible.
- No data migration.

### Recommended Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

# Phase 9 — Post-hardening decision gate `[!]`

Do not schedule implementation yet.

After Phases 6–8, choose exactly one next direction.

## Option A — Persistence and local deployment

- PostgreSQL or MySQL
- Flyway
- Docker Compose
- environment-based configuration
- persistent local demonstration

Recommended as the strongest next direction for Java backend engineering value.

## Option B — Feature Flag capability depth

- bounded multi-rule evaluation
- Feature Flag watch
- better evaluation explanation

Avoid building a generalized rule engine.

## Option C — Client SDK extraction

- rename the legacy package
- define a public client API
- typed config accessors
- refresh lifecycle
- clearer cache policy

Do not implement all three options together.

---

# Deferred and explicitly out of scope

Unless the project direction is explicitly changed, do not introduce:

- Microservices
- Redis
- Kafka or other message queues
- Distributed consensus
- Distributed locks
- Kubernetes
- Complex RBAC
- JWT login systems
- Multi-tenancy
- WebSocket or SSE migrations
- Grafana deployment
- Frontend administration UI
- Service discovery
- Configuration encryption infrastructure
- A generalized rule engine

---

# Newly discovered issue template

```markdown
### ISSUE-ID — concise title

- Severity:
- Evidence:
- Consequence:
- Proposed phase:
- Likely files:
- API/data impact:
- Verification:
- Status:
```
