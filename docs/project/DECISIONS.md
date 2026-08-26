# Durable Decisions

This file records accepted decisions and rationale. It does not turn implementation defects or speculative ideas into requirements.

## Decision A — Keep the project lightweight and local-first

Preserve a single-instance, local-oriented architecture. The project is a learning asset, so distributed platform machinery would add complexity faster than it adds learning value. Process-local rate limiting, Watch waiters, and some concurrency protection are acceptable within this boundary. Reconsider only for a concrete multi-instance requirement with an explicit scope decision.

## Decision B — Correctness and integrity before new features

Prioritize correctness, data/transaction integrity, protocol consistency, tests, reproducibility, and boundary-appropriate security before adding features. This keeps an AI-assisted learning codebase trustworthy and teachable instead of layering new behavior on uncertain invariants.

## Decision C — MySQL schema is owned by Flyway

Persistent MySQL schema evolution uses append-only Flyway migrations. Reproducible schema history is more important than convenience; editing an applied migration would make existing and fresh databases diverge. Applied migrations are immutable and future changes use a new version.

## Decision D — H2 remains the fast local/test path

Keep H2 for low-friction local development and tests. Not every unit or controller test should require Docker/MySQL, but H2 results do not prove MySQL behavior; persistent schema and database-specific behavior require the separate MySQL integration path.

## Decision E — Rollback is append-only history

Rollback restores a historical snapshot as a new current/business version and history event. Old history rows are not rewritten. This preserves auditability and causal version history, with bounded history reads controlling the resulting growth.

## Decision F — Watch uses a namespace revision

Configuration Watch tracks a persistent monotonic revision for `app + env`, not the maximum version of one configuration key. Multiple keys can change independently, so successful configuration upserts and rollbacks advance that revision in the transaction and notify only after commit.

## Decision G — Watch remains long polling

Keep long polling for the current project. It demonstrates change notification without WebSocket, SSE, MQ, or external coordination infrastructure. Waiters therefore remain process-local and require explicit capacity and lifecycle handling.

## Decision H — Persisted client cache is an untrusted boundary

Disk cache content must be structurally validated before it supplies an ETag, satisfies a 304, or becomes transient-failure fallback. Files can be stale, malformed, manually edited, or from an older client version; invalid content behaves as absent for those decisions.

## Decision I — History reads are bounded and cursor-based

History endpoints return bounded reverse-chronological pages with an exclusive business-version cursor. Append-only history can grow indefinitely, so returning all rows by default would create avoidable database, heap, and response costs.

## Decision J — Temporary plans are not project source of truth

Plans, handoffs, checklists, execution notes, and patch logs are temporary inputs or history. Permanent behavior belongs in code/tests, migrations, the architecture map, README, Project State, and Git history. Retired `dev-plan` and `patch-log` files are not restored unless the user separately asks for a durable roadmap or changelog.

## Decision K (legacy D-009) — Remote Git publication remains user-controlled (Superseded)

Status: Superseded by D-011 below. The original decision text and rationale remain preserved for auditability.

Focused local commits may be created when the active task or handoff explicitly authorizes them, but Codex must not automatically push, rewrite history, move/re-tag historical tags, or invent release identity. This preserves review and publication control without making local execution dependent on remote state.

## Decision L — Current repository evidence outranks historical snapshots

Historical contexts and migration snapshots preserve intent and rationale, but current code/tests, applied migrations, configuration, and current Git state determine implementation facts. A conflict is recorded explicitly rather than silently resolving it by copying an old snapshot.

## D-011 — Verified normal Task auto-publishes by default

Status: Accepted.

Source: latest explicit `USER_DECISION` recorded in `Config-Plan02`.

For a normal development Plan/Task, once required verification and boundary checks pass, Codex creates an appropriate commit and normally pushes it by fast-forward to the current branch's configured upstream. A separate per-task USER CHECK is not required for this normal publication path.

This supersedes Decision K (legacy D-009) because verified work should not remain indefinitely local and create delivery drift. `TASK_RESULT` must report the commit SHA, push target, push outcome, and final local/remote synchronization state; a push failure remains an explicit residual rather than a claimed success.

Force-push, non-fast-forward resolution, history rewriting, remote changes, extra remote branch creation/deletion, tag movement, release/version changes, and destructive remote/data operations remain outside this default and require explicit authorization.

## Unresolved release identity

The POM and Dockerfile use artifact version `1.0.0`, while the historical `v1.0.0` tag points to an older commit than the adoption HEAD. This is intentionally unresolved. No version bump, tag movement/re-tagging, release automation, or claim that the current HEAD is released `v1.0.0` is authorized by this migration.
