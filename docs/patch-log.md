# Patch Log

This file is append-only.

Every completed Codex patch must add one entry. Do not rewrite old entries to make past work appear cleaner. Corrections should be added as new entries.

## Entry template

```markdown
## YYYY-MM-DD — short patch title

### Goal

What behavior or defect was addressed.

### Changes

- File or component changed
- Behavior changed
- Documentation changed

### Verification

- Command: `...`
- Result: passed / failed / not run
- Relevant test cases:
  - ...

### Compatibility

- API impact:
- Data impact:
- Runtime/config impact:

### Residual risks

- Remaining limitation or follow-up
- `None` when there is no known residual risk

### Related plan items

- `docs/dev-plan.md`: Phase X / task name
```

---

## 2026-07-14 — Establish stabilization baseline

### Goal

Create the initial governance and documentation baseline for the repository before behavior refactoring begins.

### Baseline findings

- The repository uses Java 17, Spring Boot 3, and Maven multi-module.
- Existing functionality includes configuration management, Feature Flags, history, rollback, ETag, long polling, client caching, retry, basic circuit breaking, rate limiting, API Key authorization, and observability.
- Existing tests cover only part of the claimed behavior.
- The previous README contained obsolete naming and described some mechanisms more strongly than the current implementation supports.
- High-priority defects include HTTP status inconsistency, incomplete write authorization, incorrect watch cursor semantics, missing rollback notification, client watch timeout mismatch, and incomplete refetch behavior.

### Changes

- Added `AGENTS.md`.
- Added `docs/project-map.md`.
- Added `docs/dev-plan.md`.
- Added `docs/patch-log.md`.
- Replaced README content with a minimal stabilization version.

### Verification

- Command: `mvn -q -B clean verify`
- Result: not run as part of file generation
- Required next action: run after the files are placed in the repository

### Compatibility

- API impact: none
- Data impact: none
- Runtime/config impact: none

### Residual risks

- The generated documentation must be checked against the exact local working tree.
- Maven verification has not yet been executed for this documentation baseline.

### Related plan items

- `docs/dev-plan.md`: Phase 0

---

## 2026-07-14 — Phase 0 documentation validation

### Goal

Validate the new repository-governance baseline against the local working tree.

### Changes

- Verified the module names, documented Maven commands, endpoint paths, and CI workflow against the repository.
- Added the missing JDK and Maven prerequisites to `README.md`.
- Marked Phase 0 as in progress because local Maven verification cannot be run in the current environment.

### Verification

- Command: `mvn -q -B clean verify`
- Result: not run; PowerShell could not resolve `mvn`, and the repository has no Maven Wrapper.
- Relevant checks:
  - `java` resolves from the local JDK installation.
  - `.github/workflows/ci.yml` runs the documented Maven command with JDK 17.

### Compatibility

- API impact: none
- Data impact: none
- Runtime/config impact: README now states the required local tools.

### Residual risks

- Phase 0 cannot be marked complete until `mvn -q -B clean verify` passes in an environment with Maven available.

### Related plan items

- `docs/dev-plan.md`: Phase 0 / full Maven verification

---

## 2026-07-14 — Complete Phase 0 with Maven Wrapper

### Goal

Complete the repository governance baseline with reproducible Maven verification.

### Changes

- Added Maven Wrapper files: `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, and `mvnw.cmd`; the wrapper pins Maven 3.9.16.
- Made the Wrapper the canonical build entry point in `README.md`, `AGENTS.md`, `.github/workflows/ci.yml`, and `docs/project-map.md`.
- Marked Phase 0 complete in `docs/dev-plan.md`.

### Verification

- Command: `D:\Soft\CS\apache-maven-3.9.16\bin\mvn.cmd -q -B clean verify`
- Result: passed.
- Command: `D:\Soft\CS\apache-maven-3.9.16\bin\mvn.cmd wrapper:wrapper`
- Result: passed; generated Maven Wrapper pinned to Maven 3.9.16.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: none
- Data impact: none
- Runtime/config impact: Java 17 is retained; Maven Wrapper downloads the pinned Maven 3.9.16 distribution on first use.

### Residual risks

- The first Wrapper invocation requires access to Maven Central unless the distribution is already cached.

### Related plan items

- `docs/dev-plan.md`: Phase 0

---

## 2026-07-14 — Complete Phase 1 API correctness and configuration write authorization

### Goal

Make configuration write authorization and API error HTTP statuses consistent.

### Changes

- Added error code `4031` and mapped it to HTTP 403.
- Changed validation and malformed JSON responses to HTTP 400; data-integrity and optimistic-lock conflicts to HTTP 409.
- Applied the existing `app`/`env` API Key rule to configuration rollback.
- Added MockMvc regression coverage for error status/code combinations and configuration write authorization.
- Documented that Feature Flag writes intentionally remain outside API Key authorization in this phase.

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server -Dtest=ConfigControllerIntegrationTest test`
- Result: passed.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed.

### Compatibility

- API impact: error HTTP statuses now agree with existing response error codes; successful response bodies and JSON field names are unchanged.
- Data impact: none
- Runtime/config impact: configuration rollback now requires the same API Key authorization as configuration upsert.

### Residual risks

- Feature Flag write endpoints remain deliberately unauthenticated; address them only in a separately scoped security patch.

### Related plan items

- `docs/dev-plan.md`: Phase 1

---

## 2026-07-14 — Complete Phase 2 configuration watch correctness

### Goal

Replace the invalid maximum-item-version watch cursor with a persistent namespace revision.

### Changes

- Added `ConfigNamespaceRevision`, its repository, and its service, keyed by `app + env` and protected by transaction-scoped locking.
- Advanced the namespace revision once for each successful configuration upsert and rollback.
- Notified waiting clients after commit with the committed revision; rolled-back transactions do not notify or expose a revision.
- Removed `ConfigItemRepository.maxVersion` from watch semantics and closed the read/register race in the watch controller.
- Documented `sinceVersion` and `latestVersion` as namespace revisions in `examples.http` and `docs/project-map.md`.
- Added integration coverage for initial revision, repeated writes, multi-key changes, rollback, transaction rollback, timeout, immediate response, and waiting-client notification.

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server -Dtest=ConfigWatchIntegrationTest test`
- Result: passed; 8 tests, 0 failures, 0 errors.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: paths and JSON field names are unchanged; `sinceVersion` and `latestVersion` now mean namespace revision.
- Data impact: adds the `config_namespace_revision` table under the current JPA/H2 schema management.
- Runtime/config impact: none.

### Residual risks

- The in-memory waiter registry remains single-process; clustered server coordination is intentionally out of scope.
- Concurrent first creation of the same namespace relies on the database unique constraint; a conflicting transaction fails rather than silently losing a revision.

### Related plan items

- `docs/dev-plan.md`: Phase 2
