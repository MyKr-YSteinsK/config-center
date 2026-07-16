# Development Plan

Last updated: 2026-07-14

## 1. Objective

Stabilize the existing project before adding new capabilities.

The project currently contains many useful mechanisms, but several paths are incomplete or semantically inconsistent. The immediate goal is to make existing behavior:

- Correct
- Testable
- Reproducible
- Consistently documented
- Understandable as a personal learning project

## 2. Global constraints

- Preserve Java 17.
- Preserve Spring Boot 3.
- Preserve Maven multi-module.
- Do not add enterprise infrastructure during stabilization.
- Do not add new product features while P0/P1 defects remain.
- Keep each Codex task limited to one phase or one coherent subtask.
- Every code patch must update `docs/patch-log.md`.
- Every completed or newly discovered task must update this file.
- Update `docs/project-map.md` whenever architecture or behavior changes.
- Keep `README.md` minimal until stabilization is complete.
- Use `frugal-dev-runner`. Do not expand scope.

## 3. Status legend

- `[ ]` Not started
- `[-]` In progress
- `[x]` Verified complete
- `[!]` Blocked or requires a decision

A task is not complete until verification evidence exists.

## Phase 0 — Repository governance and documentation reset `[x]`

Goal: establish reliable project memory before behavior changes.

### Tasks

- [x] Add root `AGENTS.md`.
- [x] Add `docs/project-map.md`.
- [x] Add `docs/dev-plan.md`.
- [x] Add `docs/patch-log.md`.
- [x] Replace the old README with a minimal stabilization README.
- [x] Verify module names, build commands, and documented paths against the repository.
- [x] Run `D:\Soft\CS\apache-maven-3.9.16\bin\mvn.cmd -q -B clean verify` and `.\mvnw.cmd -q -B clean verify` successfully.

### Acceptance criteria

- Codex has explicit scope and documentation rules.
- README contains no known obsolete module command.
- README does not overclaim incomplete mechanisms.
- Full Maven verification result is recorded.
- Phase 0 is appended to `docs/patch-log.md`.

### API or data impact

None.

---

## Phase 1 — API correctness and write authorization `[x]`

Goal: make HTTP semantics and write protection internally consistent.

### Defects to solve

- Validation and persistence conflict handlers may return an error body with HTTP 200.
- API Key denial is represented as generic parameter invalid.
- Configuration rollback is a write operation but does not consistently enforce the same authorization as configuration upsert.
- Feature write authorization is incomplete; decide its exact scope separately rather than silently expanding security.

### Tasks

- [x] Add a dedicated authorization error code with HTTP 403.
- [x] Return explicit HTTP status codes for:
  - request body validation
  - request parameter validation
  - malformed JSON
  - data integrity conflict
  - optimistic locking conflict
- [x] Apply API Key authorization to configuration rollback.
- [x] Preserve existing successful response bodies.
- [x] Add MockMvc regression tests for status and response code.
- [x] Add authorization tests for allowed, missing, and unauthorized keys.
- [x] Document the intentionally limited Feature authorization scope.

### Acceptance criteria

- Error body code and HTTP status agree.
- Config upsert and rollback use the same app/env authorization rule.
- Unauthorized writes return 403.
- Tests prove the behavior.
- No unrelated endpoint behavior changes.

### Likely files

- `exception/ErrorCode.java`
- `exception/GlobalExceptionHandler.java`
- `controller/ConfigController.java`
- authorization-related tests
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/patch-log.md`

### Compatibility

Response HTTP status changes are behavior corrections. JSON field names should remain unchanged.

---

## Phase 2 — Configuration watch correctness `[x]`

Goal: replace the invalid maximum-item-version cursor with a real namespace revision.

### Defects to solve

Per-item `ConfigItem.version` cannot safely represent all changes in an `app/env` namespace.

A lower-version key can change while the maximum version stays unchanged, causing long polling to miss an update.

Rollback must also trigger a namespace change and notify waiting clients.

### Target design

Introduce a small persistent namespace revision model keyed by:

```text
app + env
```

Required behavior:

- Revision is monotonic for each namespace.
- Successful config upsert advances revision once.
- Successful config rollback advances revision once.
- Watch compares the namespace revision against the client cursor.
- Waiting watchers are notified only after transaction commit.
- Transaction rollback must not advance the visible revision or notify watchers.
- Preserve external JSON field names `sinceVersion` and `latestVersion` initially to avoid unnecessary API breakage.
- Document that these fields now represent namespace revision.

### Tasks

- [x] Design the minimal namespace revision entity/repository/service.
- [x] Advance revision atomically inside configuration write transactions.
- [x] Notify watchers after commit with the committed revision.
- [x] Remove use of `max(ConfigItem.version)` from watch semantics.
- [x] Add tests for:
  - initial revision
  - first upsert
  - repeated update
  - update to a different lower-version key
  - rollback
  - timeout without change
  - immediate return when revision is already newer
  - waiting client notification
- [x] Update `examples.http`.

### Acceptance criteria

- No configuration change can be missed because another key has a larger item version.
- Rollback wakes watchers.
- Tests prove multi-key correctness.
- API path and field names remain stable.

### Data impact

Adds a small namespace revision table or equivalent persistent model.

Do not add Redis, message queues, or distributed coordination in this phase.

---

## Phase 3 — Client reliability path closure `[x]`

Goal: make the client behavior match the capabilities described by the project.

### Defects to solve

- General HTTP read timeout is shorter than long-poll timeout.
- Watch `changed=true` only prints a message and does not refetch configs.
- Non-200/non-304 responses are handled too loosely.
- Retry and breaker behavior is hard to test because dependencies are constructed directly.
- Cache file name and package naming contain legacy `demo-client` remnants.

### Tasks

- [x] Separate standard request timeout from watch timeout.
- [x] Guarantee client read timeout is greater than server long-poll timeout with explicit margin.
- [x] Extract reusable configuration fetch logic.
- [x] Refetch and persist the latest config after `changed=true`.
- [x] Define response handling:
  - 200 -> accept and cache
  - 304 -> require valid cache
  - 400/403/404 -> fail without retry
  - 429 -> do not immediately retry
  - 5xx/network failure -> retry according to policy, then fallback when cache exists
- [x] Avoid treating an error response as a successful config body.
- [x] Make HTTP behavior injectable or otherwise deterministic for unit tests.
- [x] Add tests for ETag, cache fallback, retry count, 429, 5xx, watch timeout, and refetch.
- [x] Rename the cache file to `.config-center-client-cache.json` with one-time fallback migration from the legacy file.
- [x] Keep `com.example.democlient` unchanged and defer any package rename to a dedicated cleanup patch.

### Acceptance criteria

- A normal no-change watch completes without premature client timeout.
- A change notification causes an actual configuration refresh.
- Error responses are not cached as configuration.
- Fallback behavior is deterministic and tested.
- Cache migration behavior is documented.

### Compatibility

Cache-file rename may affect existing local cache. Prefer one-time migration or fallback read from the legacy file.

Do not mix package rename into the main reliability patch unless explicitly approved.

---

## Phase 4 — Server regression coverage and code cleanup `[x]`

Goal: make the stabilized behavior difficult to regress.

### Tasks

- [x] Add configuration history tests.
- [x] Add configuration rollback tests.
- [x] Add Feature Flag upsert/history/rollback tests.
- [x] Add ETag / 304 controller tests.
- [x] Add rate-limit response and metric tests where practical.
- [x] Add optimistic-lock behavior tests where deterministic.
- [x] Review transaction boundaries.
- [x] Replace unnecessarily fully qualified names in touched files only.
- [x] Remove obsolete or misleading comments in touched files.
- [x] Review whether custom metrics use instance-safe state.
- [x] Confirm JaCoCo reports are generated for useful modules.

### Acceptance criteria

- Core public behavior has regression coverage.
- Cleanup is limited to files already touched by the phase.
- No broad style-only rewrite.
- Full Maven verification passes.

---

## Phase 5 — Documentation rebuild and stable project presentation `[x]`

Goal: rebuild public documentation only after implementation is trustworthy.

### Tasks

- [x] Re-audit all routes, configuration, persistence behavior, and client behavior.
- [x] Rewrite README around verified capabilities.
- [x] Add one accurate architecture diagram.
- [x] Add one verified end-to-end local demonstration.
- [x] Add expected outputs that are generated from the current implementation.
- [x] Document known limits honestly.
- [x] Review `examples.http` against actual authorization and response behavior.
- [x] Ensure `project-map.md`, `dev-plan.md`, and README agree.

### Acceptance criteria

- README commands run.
- Every claimed major capability is backed by code and tests.
- No abandoned roadmap item is presented as current functionality.
- Stabilization phases are marked complete or remaining limitations are explicit.

---

## 4. Deferred enhancements

The following enhancements remain outside the verified baseline and are not scheduled by this plan. Implement them only when explicitly requested:

- MySQL or PostgreSQL
- Flyway
- Docker Compose
- Feature watch
- SSE or WebSocket
- Prometheus/Grafana deployment
- RBAC or JWT
- Multi-tenancy
- Frontend administration UI
- Distributed server cluster support
- Message queues
- Kubernetes

## 5. Newly discovered issue template

Add new defects below before assigning them to a phase.

### P1-ETAG-RESET — Persistent client cache can collide with reset H2 versions

- Severity: P1
- Evidence: `ConfigService.etagForList` hashes only ordered `configKey:version` pairs; the in-memory H2 database restarts business versions from 1, while `.config-center-client-cache.json` persists across server restarts.
- Consequence: different configuration values with the same keys and reused versions can produce the same ETag, causing HTTP 304 and stale client cache reuse after a database reset.
- Proposed phase: Phase 6A in `docs/config-center-dev-plan-v2.md`.
- Likely files: `ConfigService.java`, ETag/controller integration tests, README, project map, patch log.
- API/data impact: no path or JSON change; corrected ETag values will invalidate previously cached entries naturally.
- Verification: reset-style value/description changes with reused version 1 return HTTP 200 with a new ETag; the list endpoint performs one ordered repository load; matching ETags still return bodyless HTTP 304. Focused and full Maven verification passed on 2026-07-16.
- Status: resolved in Phase 6A.

### P1-WATCH-ASYNC — Long-poll trace identity and waiter lifecycle were unsafe

- Severity: P1
- Evidence: asynchronous timeout and notification callbacks created response bodies from thread-local MDC, waiter keys concatenated `app + "|" + env`, and completed waiters could leave empty namespace lists.
- Consequence: watch body trace IDs could be null or leak a write request's trace ID, distinct namespaces could collide, and the waiter map could accumulate empty entries.
- Proposed phase: Phase 6B in `docs/config-center-dev-plan-v2.md`.
- Likely files: trace filter, response factory, watch controller/notifier, watch integration tests, and project docs.
- API/data impact: no path or JSON-field change; invalid `sinceVersion` or `timeoutSeconds` values now return HTTP 400.
- Verification: timeout and notification preserve each watch request's header/body trace ID; two simultaneous watchers remain distinct; separator-containing namespaces do not collide; completed namespace entries are removed; invalid query bounds return HTTP 400. Focused and full Maven verification passed on 2026-07-16.
- Status: resolved in Phase 6B.

### P1-CLIENT-BREAKER — Caller errors contaminated availability state and cache fallback

- Severity: P1
- Evidence: `ReliableHttp` recorded every non-200/non-304 response as a breaker failure before classifying the status, and circuit-open rejection was marked cache-fallback eligible.
- Consequence: repeated 403, 404, or 429 responses could open the breaker and later make stale cache appear to be a successful configuration read.
- Proposed phase: Phase 6C in `docs/config-center-dev-plan-v2.md`.
- Likely files: `ReliableHttp.java`, `CircuitBreaker.java`, focused client tests, and project docs.
- API/data impact: no server API or cache-format change; corrected client failure classification only.
- Verification: direct deterministic tests cover CLOSED, OPEN, and HALF_OPEN transitions; repeated 403/404 keep the breaker closed; repeated 429 and circuit-open rejection cannot use stale cache; existing 5xx/network retry and exhausted-transient fallback remain covered. Focused and full Maven verification passed on 2026-07-16.
- Status: resolved in Phase 6C.

### P1-NAMESPACE-FIRST-WRITE — Concurrent first writes could collide on revision creation

- Severity: P1
- Evidence: the pessimistic revision-row query cannot lock a row that does not exist, allowing two transactions to attempt the same unique `(app, env)` insert.
- Consequence: one otherwise valid write could fail, leaving only one current row/history entry and an incomplete namespace revision.
- Proposed phase: Phase 6D in `docs/config-center-dev-plan-v2.md`.
- Likely files: namespace revision service, a bounded local lock component, watch/concurrency integration tests, and project docs.
- API/data impact: no API or schema change; concurrent first-write reliability is corrected for the single-process baseline.
- Verification: two synchronized first writes to different keys both commit, produce two current and history rows, advance revision to 2, and notify watchers with committed revisions; a held transaction exposes neither its revision nor notification before commit; rollback behavior remains covered. Focused and full Maven verification passed on 2026-07-16.
- Status: resolved in Phase 6D.

### P1-REQUEST-BOUNDARY — Invalid input reached persistence and unknown errors leaked type names

- Severity: P1
- Evidence: request DTOs did not mirror entity string limits or positive version semantics, allowlist items were unbounded, numeric query conversion lacked a dedicated handler, and the catch-all response included the exception class name without logging the stack.
- Consequence: oversized writes could fail late at the database, invalid numeric input could become HTTP 500, and operators lacked trace-linked stack diagnostics while callers received unnecessary implementation details.
- Proposed phase: Phase 7A in `docs/config-center-dev-plan-v2.md`.
- Likely files: request DTOs, controllers, global exception handler, controller integration tests, and project docs.
- API/data impact: no path or schema change; newly invalid requests return HTTP 400, and unknown HTTP 500 messages are now consistently non-sensitive.
- Verification: oversized strings, non-positive versions, invalid allowlist count/items, and numeric query type mismatches return code 4001 before service execution; unknown exceptions log their trace ID and full stack while the response contains only code 5000/message `系统异常`. Focused and full Maven verification passed on 2026-07-16.
- Status: resolved in Phase 7A.

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
