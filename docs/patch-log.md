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

---

## 2026-07-16 — Complete Phase 6C client circuit-breaker semantics

### Goal

Keep caller errors out of service-availability state and restrict stale-cache fallback to exhausted transient failures.

### Changes

- Moved breaker failure recording into the 5xx, network, and unexpected-exception branches.
- Treated received 4xx responses, including 429, as proof of service reachability while preserving immediate failure with no retry or cache fallback.
- Made circuit-open rejection ineligible for cache fallback.
- Added an injectable clock and deterministic direct tests for CLOSED, OPEN, and HALF_OPEN transitions.
- Ensured successful recovery closes a breaker opened by earlier retry attempts and unexpected HALF_OPEN exceptions reopen instead of occupying the probe permanently.
- Added regression coverage for repeated 403/404, repeated 429 with existing cache, circuit-open rejection, and retained 5xx/network retry behavior.

### Files changed

- `config-center-client/src/main/java/com/example/democlient/CircuitBreaker.java`
- `config-center-client/src/main/java/com/example/democlient/ReliableHttp.java`
- `config-center-client/src/test/java/com/example/democlient/CircuitBreakerTest.java`
- `config-center-client/src/test/java/com/example/democlient/ReliableHttpTest.java`
- `config-center-client/src/test/java/com/example/democlient/ConfigClientTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-client test`
- Result: passed; 25 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 38 tests and client 25 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- Server API impact: none.
- Cache-format impact: none.
- Client behavior impact: caller errors and circuit-open rejection no longer produce false stale-cache success; transient retry behavior remains unchanged.

### Residual risks

- The breaker remains a deliberately small in-process implementation shared per configured HTTP client, without distributed coordination or persisted state.
- Concurrent in-flight request outcomes are serialized but not correlated to individual breaker permits; high-concurrency production semantics would require a more specialized implementation and stress testing.
- HTTP 429 remains non-retryable and does not yet interpret `Retry-After`; callers must decide when to issue a later request.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 6C
- `docs/dev-plan.md`: `P1-CLIENT-BREAKER`

---

## 2026-07-16 — Complete Phase 6D namespace first-write concurrency

### Goal

Guarantee that two concurrent first writes to one local `app/env` namespace both commit and advance one monotonic revision sequence.

### Changes

- Added a fixed 64-stripe JVM-local namespace lock without an unbounded key registry.
- Acquired the stripe before revision-row lookup and held it until transaction `afterCompletion`, covering both commit and rollback.
- Preserved the existing database pessimistic lock for revision rows and the `(app, env)` unique constraint.
- Added deterministic integration coverage that synchronizes two first writes immediately before lock acquisition.
- Verified both writes, two current rows, two history rows, final revision 2, and watch notifications for revisions 1 and 2.
- Added a pre-commit visibility test proving an uncommitted revision is neither readable nor notified, while retaining the rollback regression.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/service/NamespaceRevisionLock.java`
- `config-center-server/src/main/java/com/example/configcenter/service/ConfigNamespaceRevisionService.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigWatchIntegrationTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server -Dtest=ConfigWatchIntegrationTest test`
- Result: passed; 12 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 40 tests and client 25 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API impact: none.
- Data-schema impact: none.
- Transaction impact: no independent transaction was added; configuration, history, revision, and after-commit notification retain one logical transaction boundary.

### Residual risks

- The stripes coordinate only one JVM; a future multi-instance deployment would need a database-portable cross-instance creation strategy while retaining the unique constraint.
- Hash collisions can serialize unrelated namespaces, intentionally trading some concurrency for a fixed memory bound.
- Lock release relies on the current imperative, thread-bound Spring transaction model; reactive or thread-hopping transactions are outside this project baseline.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 6D
- `docs/dev-plan.md`: `P1-NAMESPACE-FIRST-WRITE`

---

## 2026-07-16 — Complete Phase 7A request validation and exception hygiene

### Goal

Reject invalid API input before persistence and keep unknown-error diagnostics complete on the server but non-sensitive on the wire.

### Changes

- Added request DTO string limits matching configuration, Feature Flag, and history entity columns.
- Required positive expected and rollback target versions.
- Bounded Feature Flag allowlists to 20 non-blank items of at most 32 characters, conservatively fitting worst-case JSON escaping into the 4000-character column.
- Applied matching `app`, `env`, key, and name limits to read/watch query parameters.
- Translated numeric query type mismatches to HTTP 400/code `4001`.
- Logged unknown exceptions with request trace ID and full stack trace while returning only code `5000` and message `系统异常`.
- Added controller regression tests proving validation occurs before service execution and internal exception details do not enter the response.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/controller/ConfigController.java`
- `config-center-server/src/main/java/com/example/configcenter/controller/FeatureController.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/request/UpsertConfigRequest.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/request/RollbackConfigRequest.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/request/UpsertFeatureRequest.java`
- `config-center-server/src/main/java/com/example/configcenter/dto/request/RollbackFeatureRequest.java`
- `config-center-server/src/main/java/com/example/configcenter/exception/GlobalExceptionHandler.java`
- `config-center-server/src/test/java/com/example/configcenter/ConfigControllerIntegrationTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server -Dtest=ConfigControllerIntegrationTest test`
- Result: passed; 12 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 44 tests and client 25 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API paths and JSON field names are unchanged.
- Requests exceeding persistence bounds or using non-positive versions now return HTTP 400 before persistence.
- Unknown HTTP 500 responses no longer append the Java exception type to their public message.
- Data schema and existing valid data are unchanged.

### Residual risks

- Validation limits intentionally duplicate persistence limits; future entity-column changes must update DTO/controller constraints and tests together.
- Allowlist limits are conservative for the current JSON-column design and may require an explicit migration if larger lists become a product requirement.
- Full stack traces can contain sensitive internal context and therefore depend on appropriate server-log access controls.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 7A
- `docs/dev-plan.md`: `P1-REQUEST-BOUNDARY`

---

## 2026-07-16 — Complete Phase 7B rate limiter lifecycle and metrics semantics

### Goal

Bound process-local rate-limit state, group dynamic resources by matched route, validate limiter settings, and expose blocked requests with monotonic counter semantics.

### Changes

- Changed bucket identity from concrete request URI to source address, HTTP method, and Spring's matched route pattern, with the URI retained only as a fallback.
- Replaced the permanent concurrent bucket map with a synchronized access-order map capped by `rate-limit.max-buckets`, defaulting to `256`, and evicting the least-recently-used entry at capacity.
- Added startup validation requiring positive capacity and bucket count and a non-negative refill rate.
- Replaced the total-suffixed Gauge with a FunctionCounter whose Micrometer base name is `config_center_rate_limit_blocked`; Prometheus retains the conventional `_total` sample suffix.
- Added regression tests for shared dynamic-route buckets, bounded lifecycle, invalid settings, and monotonic blocked counts.

### Files changed

- `config-center-server/src/main/java/com/example/configcenter/metrics/CustomMetrics.java`
- `config-center-server/src/main/java/com/example/configcenter/web/RateLimitInterceptor.java`
- `config-center-server/src/main/java/com/example/configcenter/web/RateLimitProperties.java`
- `config-center-server/src/main/resources/application.yml`
- `config-center-server/src/test/java/com/example/configcenter/RateLimitIntegrationTest.java`
- `config-center-server/src/test/java/com/example/configcenter/web/RateLimitPropertiesTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-server '-Dtest=RateLimitIntegrationTest,RateLimitPropertiesTest' test`
- Result: passed; 4 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 47 tests and client 25 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- API paths, JSON fields, and persistence schema are unchanged.
- Valid existing rate-limit settings remain compatible; invalid zero/negative limits now fail startup validation.
- The Actuator/Micrometer base meter changes from `config_center_rate_limit_blocked_total` to `config_center_rate_limit_blocked`; Prometheus exports the FunctionCounter as `config_center_rate_limit_blocked_total`.

### Residual risks

- Rate limiting and metrics remain process-local and do not coordinate across multiple server instances.
- LRU eviction intentionally resets token history for an evicted identity, so a high-cardinality source can churn the bounded map even though memory remains bounded.
- Requests without a matched Spring route use the concrete URI fallback, but their bucket cardinality is still capped globally.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 7B
- `docs/dev-plan.md`: `P1-RATE-LIMIT-LIFECYCLE`

---

## 2026-07-16 — Complete Phase 7C client protocol validation and atomic cache writes

### Goal

Preserve request meaning for special characters, reject malformed successful responses, and prevent normal cache writes from partially overwriting the last readable file.

### Changes

- Built configuration, watch, and Feature Flag evaluation URLs with encoded URI template variables instead of string concatenation.
- Required an integral `code` equal to `0` and non-null `data` for fresh HTTP 200 responses before use.
- Required configuration data to be an array and watch data to contain a boolean `changed` plus a non-negative integral `latestVersion`.
- Classified invalid JSON, missing fields, wrong types, and nonzero success-envelope codes as non-fallback protocol errors; invalid configuration bodies are not cached.
- Serialized in-process cache writes, wrote complete JSON to a same-directory `.tmp` file, used atomic replacement where available, and fell back to replace-existing move when atomic move is unsupported.
- Kept the cache JSON structure unchanged and added deterministic encoding, protocol, replacement-failure, fallback, and concurrent-write tests.

### Files changed

- `config-center-client/src/main/java/com/example/democlient/ConfigClient.java`
- `config-center-client/src/main/java/com/example/democlient/DemoRunner.java`
- `config-center-client/src/main/java/com/example/democlient/HttpDiskCache.java`
- `config-center-client/src/main/java/com/example/democlient/HttpRequestFailedException.java`
- `config-center-client/src/test/java/com/example/democlient/ClientConfigurationTest.java`
- `config-center-client/src/test/java/com/example/democlient/ConfigClientTest.java`
- `README.md`
- `docs/project-map.md`
- `docs/dev-plan.md`
- `docs/config-center-dev-plan-v2.md`
- `docs/patch-log.md`

### Verification

- Command: `.\mvnw.cmd -q -B -pl config-center-client '-Dtest=ConfigClientTest,ClientConfigurationTest' test`
- Result: passed; 15 tests, 0 failures, 0 errors, and 0 skipped tests.
- Command: `.\mvnw.cmd -q -B clean verify`
- Result: passed; server 47 tests and client 31 tests, all with 0 failures, 0 errors, and 0 skipped tests.
- Command: `git diff --check`
- Result: passed.

### Compatibility

- Server paths, JSON field names, and persistence behavior are unchanged.
- The existing cache filename, map keys, ETag/body fields, and legacy-file migration remain readable without a schema migration.
- HTTP 304 and exhausted-transient fallback continue to use an existing cache; only fresh malformed HTTP 200 bodies are newly rejected.

### Residual risks

- Cache writes are synchronized only within one client instance; separate processes still do not coordinate access to the same user-home file.
- On filesystems without atomic move, the fallback replaces only after the temporary file is complete, but crash behavior during the final filesystem move remains provider-dependent.
- A failed disk replacement leaves the current process's in-memory entry newer than the preserved on-disk entry and emits a warning; a restart reloads the older intact file.

### Related plan items

- `docs/config-center-dev-plan-v2.md`: Phase 7C
- `docs/dev-plan.md`: `P1-CLIENT-PROTOCOL-CACHE`
