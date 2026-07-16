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

---

## 2026-07-14 — Complete Phase 3 client reliability path

### Goal

Make client retries, cache fallback, long polling, and refresh behavior deterministic and consistent.

### Changes

- Split standard and watch HTTP clients; the watch read timeout is server timeout plus an explicit margin.
- Added injectable HTTP and cache boundaries plus reusable configuration fetch/watch coordination.
- Classified responses so 200/304 succeed, 400/403/404 and 429 fail without retry, and 5xx/network failures retry before cache fallback.
- Required a valid cache for 304 and prevented error responses from being cached as configuration.
- Refetched and persisted configurations after `changed=true`.
- Renamed the cache to `.config-center-client-cache.json` with one-time migration from `.config-center-demo-client-cache.json`.
- Kept the legacy Java package unchanged for a separately scoped cleanup decision.

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-client test`
- Result: passed.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: none.
- Data impact: no server data change; the client writes a new canonical cache file and preserves the legacy file.
- Runtime/config impact: adds standard HTTP timeout settings and a watch timeout margin under `demo` configuration.

### Residual risks

- The package remains `com.example.democlient`; renaming it is intentionally deferred.
- The first cache migration preserves the old file, so users may remove it manually after confirming the new cache.

### Related plan items

- `docs/dev-plan.md`: Phase 3

---

## 2026-07-14 — Complete Phase 4 server regression coverage and code cleanup

### Goal

Protect stabilized server behavior with focused regression tests and complete bounded cleanup in the touched files.

### Changes

- Added configuration history, rollback, and deterministic optimistic-lock coverage.
- Added Feature Flag upsert/history/rollback coverage.
- Added MockMvc coverage for ETag/304 behavior and rate-limit response/metric behavior.
- Replaced the static blocked-request counter with state owned by the injected `RateLimitInterceptor` instance and bound the Micrometer gauge to that instance.
- Reviewed service transaction boundaries; writes remain transactional, reads remain read-only, and namespace revision advancement remains mandatory inside a caller transaction.
- Removed unnecessary fully qualified names and obsolete comments only in files already touched by this phase.
- Confirmed JaCoCo HTML/XML reports are generated for both server and client modules.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/controller/FeatureController.java`
- `config-center-server/src/main/java/com/example/configcenter/domain/entity/ConfigItem.java`
- `config-center-server/src/main/java/com/example/configcenter/metrics/CustomMetrics.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigService.java`
- `config-center-server/src/main/java/com/example/configcenter/service/FeatureFlagService.java`
- `config-center-server/src/main/java/com/example/configcenter/web/RateLimitInterceptor.java`
- `config-center-server/src/main/java/com/example/configcenter/web/WebConfig.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigEtagIntegrationTest.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigServiceTest.java`
- `config-center-server/src/test/java/com/example/configcenter/FeatureFlagServiceTest.java`
- `config-center-server/src/test/java/com/example/configcenter/RateLimitIntegrationTest.java`
- `docs/dev-plan.md`
- `docs/project-map.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server '-Dtest=ConfigServiceTest,FeatureFlagServiceTest,ConfigEtagIntegrationTest,RateLimitIntegrationTest' test`
- Result: passed; focused Phase 4 regression suite completed with 0 failures and 0 errors.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 34 tests and client 15 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- JaCoCo result: generated `config-center-server/target/site/jacoco/index.html` and `config-center-client/target/site/jacoco/index.html`, with corresponding XML reports.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: none; paths, JSON field names, status behavior, and authorization rules are unchanged.
- Data impact: none.
- Runtime/config impact: the rate-limit blocked metric is now scoped to the active interceptor instance instead of shared static process state; metric name and meaning are unchanged.

### Residual risks

- The H2 optimistic-lock test proves deterministic stale-entity rejection for the local baseline; behavior on a future production database should be reverified when one is introduced.
- Rate limiting remains process-local and intentionally does not coordinate across multiple server instances.
- Documentation presentation and the verified end-to-end demonstration remain for Phase 5.

### Related plan items

- `docs/dev-plan.md`: Phase 4

---

## 2026-07-14 — Complete Phase 5 documentation rebuild and stable presentation

### Goal

Replace stabilization-era documentation with a verified description of the current system, runnable commands, and an honest statement of remaining limits.

### Changes

- Re-audited every controller route, server/client configuration, JPA persistence model, and client reliability path against current code and tests.
- Rebuilt README around verified capabilities, an accurate Mermaid architecture diagram, exact API authorization scope, runtime configuration, and known limits.
- Added a PowerShell end-to-end demonstration with expected stable output fields generated from a fresh H2 server.
- Replaced duplicated and unauthorized configuration-write examples with a concise `examples.http` flow aligned to actual HTTP statuses, API Key requirements, ETag, history, rollback, watch, Feature Flag, and operations behavior.
- Updated the project map with Maven Wrapper files, namespace revision steps, exact endpoints, runtime defaults, and explicit local/single-process limits.
- Recorded the newly discovered P1 risk where persistent client cache can collide with ETags after an in-memory H2 reset; no code fix was mixed into this documentation-only phase.
- Marked all six stabilization phases complete without scheduling deferred enterprise features.

### Files changed

- `README.md`
- `examples.http`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/patch-log.md`

### Verification

- Command: `java -jar config-center-server/target/config-center-server-1.0.0.jar --server.port=18082 --rate-limit.enabled=false`
- Result: passed; server reached `UP` and the documented REST sequence created configuration version 1, returned 304 for a matching ETag, created Feature Flag version 1, returned an allowlist decision, and returned `changed=true/latestVersion=1` from watch.
- Command: `.\mvnw.cmd -q -B -pl config-center-client spring-boot:run '-Dspring-boot.run.arguments=--demo.baseUrl=http://localhost:18083 --demo.watch.enabled=false' '-Dspring-boot.run.jvmArguments=-Duser.home=D:\CS\config-center\config-center-client\target\phase5-home'`
- Result: passed; client fetched configuration version 1, evaluated the Feature Flag, printed its metrics summary, and exited normally.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 34 tests and client 15 tests completed with 0 failures and 0 errors.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: none; documentation now reflects the existing paths, fields, statuses, and authorization behavior.
- Data impact: none; runtime verification used fresh in-memory H2 processes.
- Runtime/config impact: none.

### Residual risks

- Demonstration version numbers assume a fresh server because the H2 database is in-memory; rerunning writes in one process increments versions.
- The open P1 ETag reset collision can reuse stale persistent client cache across H2 restarts; clear the client cache after resetting server data until a focused correctness patch is completed.
- Feature Flag writes, rate limiting, watch notification, H2 persistence, and the JSON client cache retain the local-only limits documented in README and the project map.
- Deferred database, security, UI, and distributed-system enhancements are not implemented or scheduled.

### Related plan items

- `docs/dev-plan.md`: Phase 5

---

## 2026-07-16 — Complete Phase 6A ETag snapshot correctness

### Goal

Make each configuration-list ETag describe the exact response snapshot and prevent stale 304 cache reuse when H2 business versions are reused.

### Changes

- Added `ConfigListSnapshot`, which loads the ordered configuration list once and carries both immutable response data and its weak ETag.
- Changed the controller to use the same service snapshot for conditional comparison and the HTTP 200 body, eliminating the former two-query race.
- Replaced delimiter-based `key:version` input with a length-prefixed SHA-256 field encoding covering every `ConfigItemDto` response field.
- Added reset-style regression coverage for reused version 1 with changed value and description.
- Added a single-query snapshot regression test, preserved bodyless HTTP 304 behavior, and added direct ambiguity/weak-format tests for ETag serialization.
- Removed the resolved ETag reset warning from README and the project map, and added the new Phase 6+ plan to repository documentation.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigService.java`
- `config-center-server/src/main/java/com/example/configcenter/service/EtagUtil.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigEtagIntegrationTest.java`
- `config-center-server/src/test/java/com/example/configcenter/EtagUtilTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server '-Dtest=ConfigEtagIntegrationTest,EtagUtilTest' test`
- Result: passed; 4 tests, 0 failures, 0 errors.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 36 tests and client 15 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: paths, JSON fields, HTTP 304 behavior, and weak ETag format are unchanged.
- Data impact: none.
- Cache impact: old ETags naturally become invalid because the representation hash input changed.

### Residual risks

- The ETag field list is explicit; future `ConfigItemDto` response-field additions must update the snapshot hash and its regression tests.
- SHA-256 collision risk is negligible for this local learning baseline.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 6A
- `docs/dev-plan.md`: `P1-ETAG-RESET`

---

## 2026-07-16 — Complete Phase 6B watch async correctness and waiter lifecycle

### Goal

Preserve each long-poll request's trace identity across asynchronous completion and make waiter indexing, cleanup, and query bounds explicit.

### Changes

- Captured the request trace ID as a servlet request attribute and added an explicit-trace success response factory.
- Replaced delimiter-composed waiter keys with an immutable namespace record and stored the namespace, originating trace ID, and deferred result in a typed waiter record.
- Built timeout and notification responses per waiter, preventing a configuration write trace ID from leaking into waiting responses.
- Removed completed waiters and empty namespace entries using atomic map operations.
- Validated `sinceVersion >= 0` and `timeoutSeconds` within `1..60`.
- Added integration coverage for timeout and notification trace identity, two distinct watchers, separator-safe namespaces, cleanup, and invalid query bounds.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/config/TraceIdFilter.java`
- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/ApiResponse.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigWatchNotifier.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigWatchIntegrationTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server -Dtest=ConfigWatchIntegrationTest test`
- Result: passed; 10 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 38 tests and client 15 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: paths and JSON field names are unchanged; invalid watch query bounds now return HTTP 400.
- Data impact: none.
- Runtime impact: waiters remain process-local and in-memory; their keys, response trace source, and cleanup semantics are now explicit.

### Residual risks

- Waiter registration and notification remain single-process only and are not coordinated across server instances.
- `CopyOnWriteArrayList` favors the expected low waiter churn of this learning project; high-volume production workloads would require fresh profiling.
- Servlet-container disconnect timing is covered by the same completion cleanup callback but is not forced deterministically by the current integration suite.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 6B
- `docs/dev-plan.md`: `P1-WATCH-ASYNC`
