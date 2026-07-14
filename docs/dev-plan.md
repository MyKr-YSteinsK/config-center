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

## Phase 2 — Configuration watch correctness

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

- [ ] Design the minimal namespace revision entity/repository/service.
- [ ] Advance revision atomically inside configuration write transactions.
- [ ] Notify watchers after commit with the committed revision.
- [ ] Remove use of `max(ConfigItem.version)` from watch semantics.
- [ ] Add tests for:
  - initial revision
  - first upsert
  - repeated update
  - update to a different lower-version key
  - rollback
  - timeout without change
  - immediate return when revision is already newer
  - waiting client notification
- [ ] Update `examples.http`.

### Acceptance criteria

- No configuration change can be missed because another key has a larger item version.
- Rollback wakes watchers.
- Tests prove multi-key correctness.
- API path and field names remain stable.

### Data impact

Adds a small namespace revision table or equivalent persistent model.

Do not add Redis, message queues, or distributed coordination in this phase.

---

## Phase 3 — Client reliability path closure

Goal: make the client behavior match the capabilities described by the project.

### Defects to solve

- General HTTP read timeout is shorter than long-poll timeout.
- Watch `changed=true` only prints a message and does not refetch configs.
- Non-200/non-304 responses are handled too loosely.
- Retry and breaker behavior is hard to test because dependencies are constructed directly.
- Cache file name and package naming contain legacy `demo-client` remnants.

### Tasks

- [ ] Separate standard request timeout from watch timeout.
- [ ] Guarantee client read timeout is greater than server long-poll timeout with explicit margin.
- [ ] Extract reusable configuration fetch logic.
- [ ] Refetch and persist the latest config after `changed=true`.
- [ ] Define response handling:
  - 200 -> accept and cache
  - 304 -> require valid cache
  - 400/403/404 -> fail without retry
  - 429 -> do not immediately retry
  - 5xx/network failure -> retry according to policy, then fallback when cache exists
- [ ] Avoid treating an error response as a successful config body.
- [ ] Make HTTP behavior injectable or otherwise deterministic for unit tests.
- [ ] Add tests for ETag, cache fallback, retry count, 429, 5xx, watch timeout, and refetch.
- [ ] Rename the cache file to `.config-center-client-cache.json` with a deliberate backward-compatibility decision.
- [ ] Decide whether package rename from `com.example.democlient` is worth a dedicated cleanup patch.

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

## Phase 4 — Server regression coverage and code cleanup

Goal: make the stabilized behavior difficult to regress.

### Tasks

- [ ] Add configuration history tests.
- [ ] Add configuration rollback tests.
- [ ] Add Feature Flag upsert/history/rollback tests.
- [ ] Add ETag / 304 controller tests.
- [ ] Add rate-limit response and metric tests where practical.
- [ ] Add optimistic-lock behavior tests where deterministic.
- [ ] Review transaction boundaries.
- [ ] Replace unnecessarily fully qualified names in touched files only.
- [ ] Remove obsolete or misleading comments in touched files.
- [ ] Review whether custom metrics use instance-safe state.
- [ ] Confirm JaCoCo reports are generated for useful modules.

### Acceptance criteria

- Core public behavior has regression coverage.
- Cleanup is limited to files already touched by the phase.
- No broad style-only rewrite.
- Full Maven verification passes.

---

## Phase 5 — Documentation rebuild and stable project presentation

Goal: rebuild public documentation only after implementation is trustworthy.

### Tasks

- [ ] Re-audit all routes, configuration, persistence behavior, and client behavior.
- [ ] Rewrite README around verified capabilities.
- [ ] Add one accurate architecture diagram.
- [ ] Add one verified end-to-end local demonstration.
- [ ] Add expected outputs that are generated from the current implementation.
- [ ] Document known limits honestly.
- [ ] Review `examples.http` against actual authorization and response behavior.
- [ ] Ensure `project-map.md`, `dev-plan.md`, and README agree.

### Acceptance criteria

- README commands run.
- Every claimed major capability is backed by code and tests.
- No abandoned roadmap item is presented as current functionality.
- Stabilization phases are marked complete or remaining limitations are explicit.

---

## 4. Deferred enhancements

Do not schedule these until Phases 1–5 are complete unless explicitly requested:

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
