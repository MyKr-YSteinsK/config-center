# Current Project State

## Adoption snapshot

This is the canonical state snapshot for the framework adoption task. It records current evidence rather than copying the external migration snapshot.

### Repository identity and delivery

- Repository: `config-center`
- Adoption-start branch: `master`
- Adoption-start HEAD: `bc9060e9a5a7a736a69747e06f6f419c45a14d2f`
- Adoption-start worktree: clean
- Tracking branch: `origin/master`; local state was ahead by 3 commits and behind by 0
- Adoption documentation kept the same branch and HEAD. The intended baseline now includes those governance documents plus the focused Watch implementation, regression tests, and targeted architecture/state updates; no schema, runtime topology, tag, or release files changed.
- Publication policy is now verified-task auto-push: after required checks and a suitable commit, normal work is pushed to the configured upstream. Force-push, history/tag/release changes, remote changes, and destructive operations remain explicitly gated.
- Before this baseline-consolidation task, local `master` was ahead of `origin/master` by 3 commits with the adoption and Watch changes uncommitted; the resulting commit/push state is reported in `TASK_RESULT` and verified from Git after publication.
- Historical tag `v1.0.0` points to commit `0bce90ce8ccfe512edc04355ded2789f5ff49524`, not the adoption HEAD. POM/Docker artifact identity remains `1.0.0`; release identity is unresolved.

## Current technical baseline

- Java 17, Spring Boot `3.5.10`, Maven multi-module build, and Maven Wrapper pinned to Maven `3.9.16`.
- `config-center-server`: Spring MVC/JPA API, current/history persistence, rollback, Feature Flag evaluation, ETag, Watch, API-key authorization, rate limiting, trace/error handling, and metrics.
- `config-center-client`: Java CLI demonstration client with HTTP retry, availability circuit breaker, response validation, ETag/disk cache, transient-failure fallback, and Watch refresh.
- `local`: in-memory H2, Hibernate `ddl-auto=update`, Flyway disabled.
- `test`: randomized in-memory H2, Hibernate `ddl-auto=create-drop`, Flyway disabled.
- `mysql`: explicit MySQL connection/API-key settings, Flyway enabled, Hibernate `ddl-auto=validate`.
- Persistent schema history consists of immutable `V1__init_schema.sql` and `V2__add_history_version_unique_constraints.sql`; no migration file was changed by adoption.
- Main Compose is a local persistent MySQL/server topology with a named development volume and loopback host publication by default. `compose.mysql-it.yml` is the separate `config_center_it` integration topology.

## Capability status

The following capabilities are present in the current source; the focused and full H2 suites above provide fresh local evidence for this checkout:

- configuration upsert/read/list, bounded history, optimistic expected-version protection, and rollback as a new version;
- Feature Flag upsert/read/evaluate, bounded history, allowlist and deterministic rollout, and rollback;
- configuration ETag/304 reads;
- namespace-revision long-poll Watch with per-waiter cursor filtering, after-commit notification, trace preservation, timeout, capacity, and lifecycle handling;
- API-key authorization for configuration/Feature Flag writes, structured errors, rate limiting, trace/metrics endpoints;
- Java CLI retry, circuit breaker, validated disk cache, fallback, and Watch refresh.

There is no active DELETE/tombstone API, reusable SDK, frontend administration system, distributed coordination layer, production backup/recovery flow, or release/publish/deploy pipeline.

## Fresh verification evidence cutoff

Evidence below was generated locally on 2026-08-26 and 2026-08-27 against the current checkout, including the focused Watch correctness fix and its regression tests.

- `mvnw.cmd -q -B -pl config-center-server '-Dtest=ConfigWatchIntegrationTest,ConfigWatchCapacityIntegrationTest' test`: PASS — 21 focused Watch/capacity tests, 0 failures, 0 errors, 0 skipped.
- `mvnw.cmd -q -B -pl config-center-server test`: PASS — 78 tests, 0 failures, 0 errors, 0 skipped.
- `mvnw.cmd -q -B -pl config-center-client test`: PASS — 38 tests, 0 failures, 0 errors, 0 skipped.
- `mvnw.cmd -q -B clean verify`: PASS — 116 tests, 0 failures, 0 errors, 0 skipped; both modules built and JaCoCo verify completed.
- `mvnw.cmd -q -B -Pmysql-it verify`: NOT RUN — the isolated MySQL service could not be started because the Docker daemon was unavailable. No MySQL/Flyway/JPA runtime result is inferred.
- `docker compose -p config-center-migration-it-20260826 -f compose.yml -f compose.mysql-it.yml config --quiet`: PASS — the isolated Compose configuration parsed successfully.
- Docker/Compose runtime smoke: NOT ESTABLISHED — Docker Compose reported that the Docker Desktop Linux engine named pipe was unavailable, so no container, healthcheck, API flow, volume operation, or restart test was performed.
- Remote CI status: not queried; repository configuration is source evidence, not a claim that a remote workflow is green.

Historical `target/` reports and old context claims are not counted as adoption-HEAD execution evidence.

## Verified correctness boundaries

### Watch future-cursor correctness

The current implementation stores each waiter's `sinceVersion` and completes it as changed only when the committed namespace revision is strictly greater than that cursor. Notifications retain future-cursor waiters, while normal cursors still complete and timeout/capacity cleanup remains covered by the server regression suite.

## Known risks and limitations

### MEDIUM — Release/version identity drift

POM/Docker artifact naming remains `1.0.0`, while historical tag `v1.0.0` is not the current HEAD. The correct release baseline is a pending user decision; no release action is part of adoption.

### MEDIUM — Process-local and local-only boundaries

Watch waiters, rate-limit buckets, and first-namespace-row locking are JVM-local; client disk cache has no encryption or cross-process lock. These are explicit learning-project limits, not evidence of multi-instance capability.

### MEDIUM — External delivery facts are unknown

The repository does not prove the existence or state of an external registry, deployed environment, backups, monitoring owner, release approval, or remote CI result. Adoption makes no claim about them.

### LOW/MEDIUM — Non-loopback deployment needs a new review

API keys are plaintext local configuration, and H2 Console, Swagger, Actuator details, and write APIs are only appropriate within the local boundary described by the project. Changing bind/publication settings requires an explicit security/deployment decision and verification.

## Adoption delta and legacy disposition

- Fresh repository identity matched the supplied migration snapshot; current evidence takes precedence.
- The old root `AGENTS.md` was replaced by a lean repo-specific contract. Its valid project boundaries were extracted into this file, `PROJECT_BRIEF.md`, `DECISIONS.md`, the supporting manifest, and the revised permanent docs.
- Deleted historical dev-plan/persistent-deployment-plan/patch-log files were not restored; Git history remains their archive.
- External old ChatGPT/Codex context, asset manifests, audits, and the migration Plan remain outside the repository as read-only history/input. Durable rationale was extracted; they are not active owners.
- No private `.env`, IDE state, Maven target/report output, credentials, or migration-package file was added to canonical project state.

## Pending USER CHECK

No adoption-blocking USER CHECK is required. The following remain pending and must be decided before the corresponding future action:

1. release/version/tag baseline and whether `1.0.0` should remain the learning-version identity;
2. external delivery facts such as registry, deployment, backup, monitoring, or approval ownership;

## Active work and checkpoint boundary

The Migration Checkpoint has passed. Current work is normal focused development within the documented local-first boundary, with verified normal Tasks following the D-011 commit-and-push policy. The baseline consolidation does not change schema, release identity, Compose topology, or network exposure. MySQL/Docker runtime evidence remains unverified locally until the Docker daemon is available.
