# Repository instructions — `config-center`

## Repository boundary

`config-center` is a lightweight, local-first configuration center and Feature Flag learning project. Keep it understandable, runnable, testable, and explainable. It is a single-instance learning project, not an enterprise control plane.

The repository contains one Spring Boot server module and one Java CLI demonstration client. Preserve Java 17, Spring Boot 3, the Maven multi-module layout, existing API paths and JSON field names, H2 fast local/test behavior, and MySQL + Flyway persistent-runtime behavior unless a task explicitly authorizes a compatibility change.

## Canonical ownership

- Current code and tests are the authority for implemented behavior.
- `config-center-server/src/main/resources/db/migration/` is the authority for persistent schema history. Applied migrations are immutable and append-only.
- `docs/project/PROJECT_BRIEF.md` owns stable product purpose, boundaries, priorities, invariants, and non-goals.
- `docs/project/DECISIONS.md` owns accepted durable decisions and their rationale, including explicitly recorded conflicts or unresolved choices.
- `docs/project/CURRENT_STATE.md` owns adoption-time repository identity, evidence cutoff, delivery facts, known risks, and pending checks.
- `docs/project/SUPPORTING_DOCS_MANIFEST.md` owns the role and currentness map for supporting assets.
- `docs/project-map.md` is the detailed architecture and runtime map; `README.md` is the public capability/build/run guide; `examples.http` is the manual request example set.
- `.github/workflows/ci.yml`, the POMs/Maven Wrapper, application profiles, Compose files, Dockerfile, and `.env.example` remain the executable/configuration authorities for their own scopes.
- External Plans, handoffs, checklists, old contexts, and patch logs are read-only execution/history inputs, not current project source of truth.

Do not restore the retired `docs/dev-plan.md`, `docs/config-center-dev-plan-v2.md`, `docs/config-center-persistent-deployment-plan.md`, or `docs/patch-log.md`. Their history remains in Git.

## Build and test semantics

Use the Maven Wrapper and Java 17. On Windows use `mvnw.cmd`; on Unix-like systems and CI use `mvnw`.

- `./mvnw -q -B -pl config-center-server test` (or the Windows equivalent) runs the server's H2-backed focused suite.
- `./mvnw -q -B -pl config-center-client test` runs the client reliability/cache suite.
- `./mvnw -q -B clean verify` builds both modules, runs the normal H2 test path, and produces JaCoCo reports.
- `./mvnw -q -B -Pmysql-it verify` runs `MysqlPersistenceIT` through Failsafe against the separately supplied MySQL integration database; it is not a substitute for the H2 fast path.

Compilation alone is not evidence for service, persistence, concurrency, client reliability, or protocol behavior. Test evidence must be labelled by the command and repository state that produced it. Historical `target/` reports are generated artifacts, not current verification.

## Persistence and schema invariants

- `local` uses in-memory H2 with Hibernate `ddl-auto=update`; `test` uses randomized in-memory H2 with `create-drop`; both disable Flyway.
- `mysql` requires explicit database URL/username/password and a nonblank API key, enables Flyway, and uses Hibernate `ddl-auto=validate`.
- Never edit applied Flyway V1/V2 files. A future schema change requires a new V3-or-later migration and corresponding verification.
- Per-item business versions, append-only history, rollback-as-new-version, and the `app + env` namespace revision are distinct invariants.

## Local runtime, data, and secrets

- Direct JAR processes default to `127.0.0.1` through `SERVER_ADDRESS`.
- The persistent Compose server binds `0.0.0.0` only inside its container; `SERVER_BIND_ADDRESS` controls host publication and defaults to `127.0.0.1`.
- The main Compose MySQL named volume is local development data. Do not run `docker compose down -v` or an equivalent destructive operation against the main project without explicit authorization.
- MySQL integration uses the dedicated `config_center_it` schema and a separate Compose project/volume (normally the `compose.mysql-it.yml` overlay). Only that isolated test topology may be reset as part of authorized integration verification.
- The MySQL application account must not be root. Keep real `.env` values, passwords, API keys, tokens, certificates, and private credentials outside Git and out of logs; `.env.example` contains placeholders only.
- Do not widen a bind address, expose a new port/endpoint, or treat local API keys, H2 Console, Swagger, or Actuator details as production security without an explicit boundary decision and verification.

## High-risk coupling and stop conditions

Treat these as coupled behavior boundaries when diagnosing or changing them:

- `ConfigService`, `ConfigNamespaceRevisionService`, `NamespaceRevisionLock`, `ConfigWatchNotifier`, and the Watch controller jointly define transaction visibility, namespace revision, after-commit notification, waiter capacity/lifecycle, trace IDs, and concurrency.
- `ReliableHttp`, `RetryPolicy`, `CircuitBreaker`, `ConfigClient`, and `HttpDiskCache` jointly define HTTP status classification, protocol validation, ETag/304 use, retry, fallback, and cache replacement.
- JPA entities, repository queries, Flyway migrations, the MySQL profile, and `MysqlPersistenceIT` jointly define the persistent schema contract.
- Compose volume names, health checks, host bindings, and `compose.mysql-it.yml` jointly define the local data boundary.

Watch behavior is a coupled product boundary; documentation/governance work alone does not authorize changing it. Record newly exposed product-contract conflicts in Project State and stop before changing product code. Stop the affected action if it would require history/tag rewriting, a version or release decision, a non-loopback deployment decision, a real secret, an applied migration edit, a destructive operation, or a remote action outside the default delivery boundary.

## Delivery boundary

For a normal development Plan/Task, after required verification passes and diff, secret, migration, and version boundaries are checked, create an appropriate focused commit and by default push it to the current branch's configured upstream. Report the commit SHA, push target, push outcome, and final local/remote sync state in `TASK_RESULT`.

- Do not force-push or use `--force-with-lease` unless the user explicitly authorizes that specific operation.
- Do not rewrite published history, change the remote URL, create or delete another remote branch during routine cleanup, move/re-tag `v1.0.0`, bump the unresolved release identity, or create release/publish/deploy automation.
- Destructive remote actions and any release/version/tag action still require explicit authorization.
- If there is no upstream, publication would require creating a remote branch, the push is non-fast-forward, or authentication/network fails, stop the publication sub-action and report it. Do not force, rewrite, rebase, or merge unknown remote changes.
