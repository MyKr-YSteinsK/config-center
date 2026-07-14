# AGENTS.md

## 1. Project identity

Repository: `config-center`

Purpose: a lightweight configuration center and Feature Flag learning project.

Technology baseline:

- Java 17
- Spring Boot 3
- Maven multi-module
- Spring Data JPA
- H2 for the current local development baseline

This is a personal learning project. Keep the implementation understandable, runnable, and verifiable. Do not expand it into an enterprise platform unless the user explicitly requests that direction.

## 2. Source-of-truth order

Before making changes, inspect these sources in this order:

1. Current code and tests
2. `docs/project-map.md`
3. `docs/dev-plan.md`
4. `docs/patch-log.md`
5. `README.md`

When documentation conflicts with code:

- Treat current code as the description of current behavior.
- Treat `docs/dev-plan.md` as the description of intended future behavior.
- Explicitly record the inconsistency before deciding whether to change code or documentation.
- Never silently rewrite documentation to hide an implementation defect.

## 3. Mandatory workflow

For every task:

1. Read the relevant code, tests, and documentation before editing.
2. State the exact defect or target behavior.
3. Keep the patch small and limited to the requested phase.
4. Add or update tests that prove the behavior.
5. Run the smallest relevant test set first.
6. Run the full Maven verification before completion when feasible.
7. Update the required documentation in the same patch.
8. Append a concise entry to `docs/patch-log.md`.
9. Report changed files, verification results, residual risks, and documentation updates.

Use `frugal-dev-runner`. Do not expand scope.

## 4. Documentation synchronization rules

Every code change must update documentation according to this matrix.

### Always update

- `docs/patch-log.md`
  - Append one entry for every completed patch.
  - Include behavior changed, files changed, verification, and remaining risks.

- `docs/dev-plan.md`
  - Update phase/task status.
  - Record newly discovered defects.
  - Do not mark a task complete without verification evidence.

### Update when applicable

- `docs/project-map.md`
  - Update when modules, packages, classes, API routes, data flow, persistence model, concurrency model, watch semantics, client behavior, or runtime commands change.

- `README.md`
  - Keep minimal during the stabilization period.
  - Update only when stable public capabilities, module names, prerequisites, or run commands change.
  - Do not add ambitious roadmap language or claim behavior that is not covered by tests.

## 5. Scope discipline

Default priorities:

1. Correctness defects
2. API and data consistency
3. Tests and reproducibility
4. Maintainability and clarity
5. Documentation accuracy
6. New capabilities

Do not add new features while unresolved P0 or P1 correctness work remains, unless the user explicitly overrides this rule.

Avoid by default:

- Microservices decomposition
- Message queues
- Kubernetes
- Complex RBAC
- Multi-tenancy
- Distributed consensus
- External cache clusters
- Grafana dashboards
- WebSocket or SSE migrations
- Frontend administration systems
- Premature abstractions or framework replacement

## 6. Compatibility rules

Unless a phase explicitly authorizes a breaking change:

- Preserve Java 17.
- Preserve Spring Boot 3.
- Preserve the Maven multi-module structure.
- Preserve existing API paths and JSON field names.
- Preserve existing database meaning where possible.
- Do not rename packages or modules as incidental cleanup.
- Do not mix behavior fixes with broad formatting or comment rewrites.

If an API or persistence change is necessary, document:

- Previous behavior
- New behavior
- Compatibility impact
- Migration or fallback
- Verification

## 7. Testing rules

Prefer tests that prove externally meaningful behavior.

Minimum expectations by change type:

- Service behavior: focused unit or Spring service tests
- Controller behavior: MockMvc integration tests, including HTTP status and response body
- Persistence or concurrency behavior: repository/integration tests
- Long polling: timeout, notification, rollback notification, and multi-key change tests
- Client reliability: deterministic unit tests with injected HTTP behavior; do not rely only on manual server startup
- Documentation-only changes: validate commands and paths against the repository

Do not treat compilation alone as sufficient verification.

Recommended commands:

```bash
./mvnw -q -B -pl config-center-server test
./mvnw -q -B -pl config-center-client test
./mvnw -q -B clean verify
```

If a command cannot be run, say so explicitly and do not claim success.

## 8. Patch quality

A good patch should:

- Solve one coherent problem
- Be explainable in terms of one behavior change
- Include regression tests
- Avoid unrelated renames and formatting churn
- Keep comments factual and concise
- Leave the repository in a runnable state
- Update docs in the same patch

A patch is incomplete when code changed but the relevant docs and patch log were not updated.
