# Project Brief

## Purpose

`config-center` is a lightweight configuration center and Feature Flag learning project. It gives the project owner a concrete, locally runnable example of configuration versioning, rollback, change observation, Feature Flag evaluation, client reliability, persistence, and testing.

The project values technical credibility and incremental learning while remaining understandable to its owner. It is not intended to become an enterprise control plane by default.

## Product boundary

- One Spring Boot server and one Java CLI demonstration client.
- Local-first and single-instance by default.
- H2 is the zero-dependency local/test path.
- MySQL plus Flyway is the persistent-runtime path.
- Docker Compose is a reproducible local persistence path, not a production platform or backup strategy.
- Lightweight API-key authorization is appropriate to the current local learning boundary.

## Stable priorities

1. Correctness and data/transaction integrity.
2. API and protocol consistency.
3. Reproducible tests and local operation.
4. Explainability and maintainable, small changes.
5. Security appropriate to the actual deployment boundary.
6. New capabilities only after the existing behavior is understood and stable.

## Invariants

- Configuration and Feature Flag history is append-only; rollback creates a new current/business version.
- Per-item business versions remain distinct from the persistent `app + env` namespace revision used by Watch.
- Successful configuration writes advance the namespace revision transactionally, and Watch visibility is post-commit.
- History reads remain bounded and cursor-based.
- Persisted client cache is untrusted input and must pass response-shape validation before it supplies an ETag, 304 body, or transient-failure fallback.
- Persistent schema evolution uses immutable, append-only Flyway migrations.
- Local defaults remain loopback-oriented, and real credentials remain outside Git.

## Non-goals

No default expansion to microservices, message queues, Kubernetes, Redis or other external coordination, multi-tenancy, complex RBAC/JWT/account management, frontend administration, distributed Watch/rate limiting, production backup/recovery, or production release infrastructure. DELETE/tombstone semantics, SDK extraction, and other future ideas require a separate product decision.

## Related canonical documents

See `docs/project/DECISIONS.md` for durable choices, `docs/project/CURRENT_STATE.md` for the current evidence snapshot, `docs/project/SUPPORTING_DOCS_MANIFEST.md` for supporting-document ownership, and `docs/project-map.md` for detailed architecture.
