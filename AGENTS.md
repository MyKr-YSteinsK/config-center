# AGENTS.md

## 1. Project identity

Repository: `config-center`

Purpose: a lightweight configuration center and Feature Flag learning project.

Technology baseline:

- Java 17
- Spring Boot 3
- Maven multi-module
- Spring Data JPA
- H2 for fast local and test scenarios
- MySQL + Flyway for persistent runtime
- Docker Compose for local persistent deployment
- GitHub Actions for build and MySQL integration verification

This is a personal learning project. Keep the implementation understandable, runnable, testable, and explainable.

Do not expand it into an enterprise platform unless the user explicitly requests that direction.

## 2. Source-of-truth order

Before making changes, inspect these sources in this order:

1. Current code and tests
2. Database migrations under `db/migration`
3. `docs/project-map.md`
4. `README.md`
5. Other permanent design or operational documentation
6. Git history when historical context is necessary

Interpretation rules:

- Current verified code and tests describe current behavior.
- Flyway migrations describe the persistent schema history.
- `docs/project-map.md` describes the current architecture and important runtime paths.
- `README.md` describes verified public capabilities and usage.
- Git history describes how and why the project evolved.

Temporary task plans, phase briefs, Codex handoffs, execution checklists, and scratch notes are not project source-of-truth documents.

When documentation conflicts with code:

- Treat verified code and tests as the current behavior.
- Treat applied Flyway migrations as the current persistent schema history.
- Explicitly identify the inconsistency.
- Decide whether code or permanent documentation should change.
- Do not edit temporary planning material merely to record progress.
- Never silently rewrite documentation to hide an implementation defect.

## 3. Temporary plans and handoffs

A user-supplied plan, phase document, task brief, checklist, or Codex handoff is read-only execution input unless the user explicitly says otherwise.

Default rules:

- Do not edit it.
- Do not mark tasks complete inside it.
- Do not rename or relocate it.
- Do not commit it.
- Do not create a replacement plan.
- Do not copy completion evidence into it.
- Do not treat it as permanent architecture documentation.
- Do not add it to `docs/project-map.md`.
- Do not update `docs/dev-plan.md` or `docs/patch-log.md` merely because a task was completed.

Completion is recorded through:

- implementation changes;
- regression tests;
- the focused Git commit;
- necessary permanent documentation updates;
- the final execution report.

Temporary plans should normally live outside the repository or in a Git-ignored directory such as `.handoffs/`.

## 4. Mandatory workflow

For every task:

1. Inspect the current branch, recent commits, working tree, relevant code, tests, migrations, and permanent documentation.
2. State the exact defect or target behavior.
3. Treat supplied plans and handoffs as read-only unless explicitly authorized.
4. Keep the patch limited to one coherent behavior change.
5. Add or update tests that prove the change.
6. Run the smallest relevant verification first.
7. Run full Maven verification before completion when feasible.
8. Run environment-specific verification when the task changes MySQL, Flyway, Docker, Compose, or CI behavior.
9. Update permanent documentation only when the implemented behavior makes it inaccurate.
10. Report changed files, verification results, compatibility impact, residual risks, and any manual follow-up.
11. Create one focused commit only when the user or handoff explicitly requests it.
12. Never push, rewrite history, force-update a branch, delete volumes, or expose secrets unless explicitly requested.

Use `frugal-dev-runner`. Do not expand scope.

## 5. Documentation rules

Documentation updates are behavior-driven, not mandatory for every patch.

### Update `docs/project-map.md` when applicable

Update it when the patch changes:

- module or package responsibilities;
- API routes;
- persistence model;
- Flyway migration structure;
- transaction or concurrency behavior;
- Watch semantics;
- client behavior;
- runtime profiles;
- Docker or deployment topology;
- CI architecture.

Do not update it for implementation details that leave the documented architecture unchanged.

### Update `README.md` when applicable

Update it when the patch changes:

- verified public capabilities;
- prerequisites;
- build, test, or run commands;
- environment variables;
- authorization requirements;
- externally visible API behavior;
- Docker or MySQL usage;
- important user-facing limitations.

Do not claim a capability unless it is implemented and verified.

### `docs/dev-plan.md`

`docs/dev-plan.md` is optional.

Update it only when:

- the user explicitly requests a durable roadmap;
- the task is specifically about maintaining that roadmap.

Do not use it as a mandatory per-patch status tracker.

### `docs/patch-log.md`

`docs/patch-log.md` is optional.

Update it only when:

- the user explicitly requests a durable human-readable change log;
- a major architectural milestone needs context beyond the Git commit.

Routine changes should rely on focused commits, tests, and the final execution report.

## 6. Scope discipline

Default priorities:

1. Correctness defects
2. Data and transaction integrity
3. API and protocol consistency
4. Tests and reproducibility
5. Security appropriate to the current deployment boundary
6. Maintainability and clarity
7. Documentation accuracy
8. New capabilities

Do not add new features while unresolved P0 or P1 correctness work remains unless the user explicitly overrides this rule.

Avoid by default:

- Microservices decomposition
- Message queues
- Kubernetes
- Complex RBAC
- Multi-tenancy
- Distributed consensus
- Distributed locks
- External cache clusters
- Grafana dashboards
- WebSocket or SSE migrations
- Frontend administration systems
- General-purpose rule engines
- Premature abstractions
- Framework replacement
- Broad package renaming
- Repository-wide formatting churn

Do not solve a local environment problem by hard-coding machine-specific mirrors, credentials, paths, or network settings into the repository.

## 7. Compatibility rules

Unless the task explicitly authorizes a breaking change:

- Preserve Java 17.
- Preserve Spring Boot 3.
- Preserve the Maven multi-module structure.
- Preserve existing API paths and JSON field names.
- Preserve existing database meaning.
- Preserve H2 fast-test behavior.
- Preserve MySQL + Flyway persistent-runtime behavior.
- Do not modify an applied Flyway migration.
- Add a new `V2__...`, `V3__...`, or later migration for schema changes.
- Do not rename packages or modules as incidental cleanup.
- Do not mix behavior fixes with broad formatting, comment rewrites, or dependency upgrades.

If an API, cache format, runtime configuration, or persistence change is necessary, report:

- previous behavior;
- new behavior;
- compatibility impact;
- migration or fallback;
- verification.

## 8. Security and secrets

Never commit or print:

- `.env`;
- database passwords;
- MySQL root credentials;
- API keys;
- GitHub secrets;
- tokens;
- private certificates;
- personal machine credentials.

Use placeholders in `.env.example`.

The application must not use the MySQL root account.

Treat the current Compose deployment as local-only unless the task explicitly introduces and verifies a stronger deployment boundary.

Do not expose new ports, Actuator endpoints, Swagger, metrics, or write APIs to untrusted networks without explicitly documenting and testing the security impact.

## 9. Testing rules

Prefer tests that prove externally meaningful behavior.

Minimum expectations by change type:

- Service behavior: focused unit or Spring service tests
- Controller behavior: MockMvc integration tests, including HTTP status and response body
- Persistence or concurrency behavior: repository or integration tests
- Flyway changes: clean-database migration plus JPA validation
- Long polling: timeout, notification, rollback notification, multi-key change, and waiter lifecycle tests
- Client reliability: deterministic tests with injected HTTP behavior
- Cache behavior: malformed, stale, migration, and fallback tests
- Docker or Compose changes: config validation, image build, health checks, API flow, and persistence across restart
- CI changes: confirm the relevant remote workflow run succeeds
- Documentation-only changes: validate commands, paths, and claims against the repository

Do not treat compilation alone as sufficient verification.

Recommended commands:

```bash
./mvnw -q -B -pl config-center-server test
./mvnw -q -B -pl config-center-client test
./mvnw -q -B clean verify
```

For Windows:

```powershell
.\mvnw.cmd -q -B -pl config-center-server test
.\mvnw.cmd -q -B -pl config-center-client test
.\mvnw.cmd -q -B clean verify
```

For MySQL integration work, use the repository-defined MySQL integration profile or workflow.

For Docker work, use the repository's Compose commands and preserve named volumes unless destructive reset is explicitly required.

If a verification command cannot run:

- state the exact blocker;
- record which checks did run;
- do not claim success;
- do not redesign the implementation merely to bypass a local network or tool problem.

## 10. Git and patch quality

A good patch should:

- solve one coherent problem;
- be explainable as one behavior change;
- include appropriate regression tests;
- avoid unrelated renames and formatting churn;
- keep comments factual and concise;
- leave the repository runnable;
- preserve secrets and local environment boundaries;
- update permanent documentation only when required;
- exclude temporary plans and execution artifacts.

Before committing:

- inspect `git status`;
- inspect the diff;
- run `git diff --check`;
- confirm no secret or ignored local file is staged;
- confirm migration files follow append-only Flyway rules;
- confirm Linux scripts have required executable bits.

A patch is incomplete when:

- implementation or tests are incorrect;
- verification was skipped without disclosure;
- a real public or architectural change leaves permanent documentation inaccurate;
- secrets or temporary execution artifacts are included;
- scope expanded beyond the task.

A patch is not incomplete merely because a temporary plan, `docs/dev-plan.md`, or `docs/patch-log.md` was not updated.

## 11. Final execution report

The final Codex response should include:

- Goal completed
- Changed files
- Behavior changed
- Tests and commands run
- Exact verification result
- Compatibility impact
- Documentation updated, if any
- Manual steps still required
- Residual risks
- Commit SHA, when a commit was requested

Do not report a task as complete when required verification failed or did not run.
