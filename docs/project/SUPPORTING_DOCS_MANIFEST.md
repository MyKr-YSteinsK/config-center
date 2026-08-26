# Supporting Documents Manifest

This manifest makes the active ownership of repository assets explicit. It is an ownership map, not a task tracker.

| Asset | Canonical owner / role | Adoption status and currentness | Visibility |
|---|---|---|---|
| `AGENTS.md` | Lean repository-specific governance, safety boundaries, command semantics, and ownership pointers | Replaced during adoption; does not own generic Skill workflow or retired plan/patch-log tracking | Repository-root instruction visible to Codex and contributors |
| `docs/project/PROJECT_BRIEF.md` | Stable product purpose, boundary, priorities, invariants, and non-goals | Created during adoption; current at the adoption evidence cutoff | Tracked canonical project state visible to agents and contributors |
| `docs/project/DECISIONS.md` | Accepted durable decisions, rationale, superseded decisions, and unresolved release identity | Created during adoption; current with the D-011 publication-policy transition | Tracked canonical project state visible to agents and contributors |
| `docs/project/CURRENT_STATE.md` | Current repository identity, evidence cutoff, capabilities, risks, delivery state, and pending checks | Created during adoption; current after final verification update | Tracked canonical project state visible to agents and contributors |
| `docs/project/SUPPORTING_DOCS_MANIFEST.md` | Supporting-asset ownership/currentness map | Created during adoption | Tracked canonical project state visible to agents and contributors |
| `README.md` | Public capability overview, prerequisites, build/run instructions, API summary, local boundaries, and user-facing limitations | Targeted revision during adoption; current claims distinguish repository support from fresh execution evidence | Public repository documentation |
| `docs/project-map.md` | Detailed architecture, module responsibilities, data flow, runtime profiles, behavior paths, and limits | Targeted re-review during adoption; current review date and per-waiter Watch cursor behavior recorded | Tracked architecture documentation visible to agents and contributors |
| `examples.http` | Manual HTTP request examples aligned with the current API | Inspected; retained unchanged because no actual API mismatch was found; not an automated test | Tracked manual-use artifact |
| `config-center-server/src/main/resources/db/migration/V1__init_schema.sql` and `V2__add_history_version_unique_constraints.sql` | Immutable persistent MySQL schema history | Inspected; intentionally unchanged; future changes require V3 or later | Tracked executable migration input |
| `.github/workflows/ci.yml` | Executable H2 build/test and MySQL integration CI reference | Inspected; intentionally unchanged; workflow configuration is not proof of a current remote run | Tracked CI executable reference |
| `compose.yml` | Local persistent MySQL/server topology and main development volume boundary | Inspected; intentionally unchanged; local-only and non-destructive volume boundary retained | Tracked local runtime definition |
| `compose.mysql-it.yml` | Isolated MySQL integration overlay, schema, loopback test port, and separate project/volume topology | Inspected; intentionally unchanged; use only for isolated integration verification | Tracked test-runtime definition |
| `.env.example` | Placeholder local Compose/manual-MySQL configuration reference | Inspected; intentionally unchanged; contains placeholders only | Tracked placeholder configuration reference |
| `pom.xml`, module POMs, `.mvn/`, `mvnw`, `mvnw.cmd` | Java/Maven/module/dependency/lifecycle build contract | Inspected; intentionally unchanged | Tracked build inputs and entry points |
| `config-center-server/Dockerfile` and `.dockerignore` | Local server image build/runtime boundary | Inspected; intentionally unchanged; no image publish or release identity is implied | Tracked local container-build definition |
| Server/client source and tests | Implemented behavior and regression evidence | Kept active; the current Watch correctness task updates only the server Watch path and its regression coverage | Tracked implementation and test sources |
| `.env`, `.idea/**`, `**/target/**`, Surefire/JaCoCo output | Private local configuration, IDE state, and generated artifacts | Kept ignored/private; not read into state, copied, or committed | Local-only/ignored; not canonical project knowledge |

## Retired and external assets

- `docs/dev-plan.md`, `docs/config-center-dev-plan-v2.md`, `docs/config-center-persistent-deployment-plan.md`, and `docs/patch-log.md` are deleted historical assets. They are archived by Git history and are not restored or active owners.
- Old ChatGPT/Codex contexts, repository audits, asset manifests, and one-time migration prompts are external read-only history/input. Durable project-specific information is extracted into the canonical files above; the external files are not copied into the repository.
- There is no separate authoritative visual/UX specification, API contract document, data dictionary, ADR set, release runbook, backup runbook, or changelog. The repository does not create empty stand-ins for those absent assets.
