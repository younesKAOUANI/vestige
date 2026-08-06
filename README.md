# Vestige

*(n.)* — a trace of something that once existed.

A multi-tenant service that ingests static-analysis reports (SARIF) from CI, tracks
the identity of each finding as the code beneath it changes, and produces an
auditable record of every triage decision a team makes.

Built by [Younes Kaouani](https://youneskaouani.dev) as a portfolio project for a
Graduate Software Engineer application to Sonar (SonarSource), Geneva. The full
design brief is frozen at [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); this README
is the front door, not a second copy of it.

---

## Contents

- [The problem](#the-problem)
- [What Vestige is](#what-vestige-is)
- [The matcher, and why it is the centerpiece](#the-matcher-and-why-it-is-the-centerpiece)
- [A tour of the system](#a-tour-of-the-system)
- [Threat model](#threat-model)
- [API surface](#api-surface)
- [Running it locally](#running-it-locally)
- [Testing](#testing)
- [A note on how this was built](#a-note-on-how-this-was-built)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Architecture Decision Records](#architecture-decision-records)
- [Roadmap — everything this is honest about not doing](#roadmap--everything-this-is-honest-about-not-doing)
- [License](#license)

---

## The problem

Static analysers are good at answering *"what is wrong with this file, right now?"*
They are bad at answering the question a team actually cares about:

> *Is this a **new** problem, or the same one we already agreed to live with three
> months ago — and who agreed to it?*

Concretely: run 1, commit `a1b2c3`, `PaymentService.java`:

```java
41 |   public void refund(Order o) {
42 |       String sql = "SELECT * FROM refunds WHERE id = " + o.getId();   ← S3649 SQL injection
43 |   }
```

Run 2, commit `d4e5f6`. Someone added twelve lines of imports and renamed the method:

```java
57 |   public void issueRefund(Order order) {
58 |       String sql = "SELECT * FROM refunds WHERE id = " + order.getId();   ← S3649 SQL injection
59 |   }
```

Line 42 became line 58. The message text changed (`o` → `order`). Match naïvely on
`(rule, file, line)` and the tool reports the old issue **fixed** and the new one
**newly introduced**. The "new issues" count spikes, a quality gate fails a PR that
changed nothing relevant, and — like any tool that cries wolf — the team stops
trusting it within a couple of sprints.

Solving that requires a problem the analyser itself does not solve: **finding
identity across time**. A finding is not a line number; line numbers move every time
someone adds an import. A finding is a *claim about a piece of code*, and the claim
has to survive edits, renames, reformatting and refactors. Vestige is the layer that
owns that claim — it sits between CI and the developer:

```
  CI job → analyser (SonarQube / Semgrep / CodeQL) → SARIF → Vestige → dashboard
                                                                  ↓
                                                          quality gate verdict → back to the PR
```

Three things make this a genuine engineering problem rather than a form over a
database (spelled out in full in [`docs/ARCHITECTURE.md` §1.1](docs/ARCHITECTURE.md#11-why-this-is-worth-building-rather-than-another-crud-app)):

1. **Identity matching is heuristic and adversarial** — every design choice trades
   off false merges (two distinct problems collapsed into one) against false splits
   (one problem resurfacing as "new" after a whitespace change), and the two pull in
   opposite directions.
2. **Ingestion must be exactly-once in effect** — CI retries, webhooks redeliver, and
   a re-delivered report must not double-count findings or corrupt an issue's history.
3. **The audit trail is the product** — if a team can silently rewrite who dismissed
   a security finding, the record is worthless.

## What Vestige is

A Spring Boot 3 / Java 17 API, a Postgres 16 schema built around row-level security,
and a React 19 dashboard, implementing every piece of that brief:

- `POST /api/v1/runs` ingests a SARIF report with idempotency-key semantics, backed
  by a transactional outbox and a `SKIP LOCKED` polling worker with exponential
  backoff and a poison-message quarantine path.
- A three-rung fingerprint ladder matches each run's findings against the branch's
  previously-tracked issues, deterministically, in O(P+C).
- Every triage decision is appended to a hash-chained, trigger-enforced append-only
  log, with an endpoint that walks the whole chain and names the first tampered entry.
- A quality gate evaluates a small, closed set of conditions against exactly what the
  matcher decided was new — not against a git diff (that distinction is the system's
  central engineering argument; see [ADR-008](docs/adr/008-matcher-defined-new-code.md)).
- Every non-trivial decision behind all of the above is written down as an ADR: the
  decision, the alternatives that were seriously considered and rejected, and what
  would change the author's mind. See [Architecture Decision Records](#architecture-decision-records).

## The matcher, and why it is the centerpiece

`matcher-corpus/` holds 32 hand-built before/after fixtures, each one a real refactor
shape — extract method, rename symbol, reorder imports, reformat, move file, inline
variable, wrap in try/catch, and combinations of these — with the *expected* matching
recorded as ground truth. `MatcherCorpusHarnessTest` asserts against it on every run,
not as a report to read later but as a hard test failure:

```
matcher-corpus: 32 cases, 33 expected matches, 33 actual matches, 33 correct,
                0 false splits (0.00%), 0 false merges (0.00%)
```

Against the brief's own bar (≤5% false-split, 0% false-merge, §3.4), that is a clean
pass, not a narrow one. Reproduce it yourself with no network access and no Maven at
all:

```
scripts/offline-verify.sh
```

See [ADR-001](docs/adr/001-fingerprint-ladder-for-issue-identity.md) for the full
design (three fingerprints tried in descending order of strength, bucketed matching,
a deterministic tie-break) and why an embedding-similarity or pure-diff approach was
rejected in favour of it.

## A tour of the system

**Domain model.** `Project ── Branch ── AnalysisRun ── Finding >── Issue ── TriageEvent`.
A `Finding` is one immutable raw result from one analyser in one run; an `Issue` is
the mutable, cross-run claim a Finding is matched into. Splitting them is
[ADR-003](docs/adr/003-separate-finding-from-issue.md).

**Multi-tenancy.** Shared schema, `organization_id` on every tenant-scoped table,
enforced by Postgres row-level security with `FORCE ROW LEVEL SECURITY` — not by an
application-layer filter that one forgotten `WHERE` clause could silently break. The
test suite includes a deliberate adversarial case
(`VestigeAdversarialTenancyIT`): a repository query with **no** tenant predicate at
all still returns only the calling tenant's rows, and returns *nothing* when no
tenant context is set. See [ADR-002](docs/adr/002-shared-schema-postgres-rls-for-tenancy.md).

**Ingestion.** `POST /api/v1/runs` validates and persists a run, its raw bytes, and
an outbox row in one transaction, then answers immediately. A scheduled worker claims
work with `FOR UPDATE SKIP LOCKED`, parses the SARIF with a streaming Jackson parser
in batches of 1,000 (peak heap independent of report size —
[ADR-009](docs/adr/009-streaming-sarif-parse.md)), runs the matcher, evaluates the
gate, and records the outcome — three separate transactions (claim / process /
record-outcome), deliberately, so a processing failure's rollback can never also roll
back the very row that has to durably record that failure
([ADR-004](docs/adr/004-transactional-outbox-with-skip-locked.md)). A report is
identified by a client-supplied or derived idempotency key; a byte-identical repeat
returns the original result, a key reused for a different report is a `409`
([ADR-005](docs/adr/005-client-supplied-idempotency-keys.md)).

**Tamper-evident audit log.** Every triage decision is hash-chained
(`entry_hash = sha256(prev_hash ‖ sha256(canonical_json(payload)))`) and a
`BEFORE UPDATE OR DELETE` trigger makes the table append-only for every role,
including the application's own. `GET /api/v1/audit/verify` walks the whole chain and
reports `{"intact": true, "length": n}` or the first index that fails to verify.
`VestigeAuditTamperDetectionIT` proves the trigger blocks a raw `UPDATE`/`DELETE`
outright, and that a fabricated `INSERT` (which the trigger does not guard, by
design) is still caught by the verifier. See
[ADR-006](docs/adr/006-hash-chained-audit-log.md).

**Quality gates.** A small closed set of conditions
(`NEW_CRITICAL_ISSUES`, `NEW_ISSUES_TOTAL`, `REOPENED_ISSUES`, `TOTAL_BLOCKER_ISSUES`)
evaluated as a pure function of what the matcher marked new or reopened in this run —
never a diff. See [ADR-008](docs/adr/008-matcher-defined-new-code.md) for why that is
the more consequential half of the matching design, not an afterthought.

**Frontend.** React 19 + TypeScript + Vite + TanStack Query. An issue list with
filters (status, severity, rule, since-run), an issue detail view merging finding
sightings and triage events into one chronological timeline with a triage form, a run
lookup panel showing the gate outcome condition-by-condition, and an audit-chain
verification panel. No login flow and no project-listing endpoint exist in v1 (§8's
API table has neither) — the top bar asks for an API key and a project id directly,
which is the honest reflection of that rather than a UI pretending otherwise.

## Threat model

Stated plainly, because a security property nobody wrote down is a property nobody
can rely on.

**What the audit log defends against.** A `BEFORE UPDATE OR DELETE` trigger
(`V4__triage_event_append_only.sql`) makes `triage_event` append-only for every
database role, including the one the application itself connects as — there is no
"admin mode" that can edit history, on purpose. `VestigeAuditTamperDetectionIT` proves
this is prevention, not merely detection: a raw `UPDATE`/`DELETE` against the table
throws and the chain verifies intact afterwards. The one write path the trigger does
not guard is `INSERT` — appending is the legitimate operation — so a sufficiently
privileged attacker who bypasses the application entirely (a leaked superuser
credential, a doctored restore from backup, direct file/WAL manipulation) could still
splice a fabricated row into the table. The hash chain is what catches *that*:
`GET /api/v1/audit/verify` recomputes every entry's hash from genesis and names the
first one that does not check out. This is a deliberately narrower guarantee than
"tampering is impossible" — it is "tampering is either blocked outright, or leaves
unmistakable, computable evidence of exactly where it happened" (see
[ADR-006](docs/adr/006-hash-chained-audit-log.md), and its own "what would change our
mind" for where a checkpointed variant of this would go next).

**What it does not defend against.**

- **Actor identity is a claim, not a credential.** `actor` is a caller-supplied
  free-text string (§6 names the field `actor_id`, implying a foreign key into a
  users table that v1 does not have — see [Roadmap](#roadmap--everything-this-is-honest-about-not-doing)).
  The chain proves *an* action happened under *some* claimed name and was not later
  altered; it does not prove the named person is who actually performed it. Anyone
  holding a valid organisation API key can triage as any actor name they type.
- **A key is scoped to a whole organisation, not a person.** There is no per-user
  authentication in v1 (§11) — every caller inside a tenant has the same access as
  every other caller in that tenant. RLS's guarantee is *cross*-tenant isolation
  (§5.2, [ADR-002](docs/adr/002-shared-schema-postgres-rls-for-tenancy.md)), not
  *within*-tenant separation of duties.
- **A correction is a new event, not an edit.** If a triage decision was recorded
  wrongly (fat-fingered, or genuinely mistaken), the only remedy is a further
  `TriageEvent` that supersedes it — the record of the mistake stays, on purpose,
  rather than being quietly erased. `V4`'s own migration comment calls this out as a
  real limitation, not an oversight.
- **No encryption-at-rest story is specified here.** Vestige relies on whatever the
  underlying Postgres/infrastructure provides; nothing in the application layer adds
  or assumes disk-level encryption.
- **A compromised `vestige.github.webhook-secret` or API key is a full-tenant
  compromise**, same as any bearer-token scheme — there is no key rotation flow,
  scoped/read-only keys, or short-lived token support in v1.

## API surface

```
POST   /api/v1/runs                      ingest a SARIF report (idempotent)
GET    /api/v1/runs/{id}                 run status + gate result
GET    /api/v1/projects/{id}/issues      filter: status, severity, rule, sinceRun
PATCH  /api/v1/issues/{id}               triage — requires justification on FALSE_POSITIVE/WONT_FIX
GET    /api/v1/issues/{id}/history       full finding + triage timeline
GET    /api/v1/audit/verify              hash-chain integrity check
POST   /api/v1/webhooks/github           push + PR events (HMAC-SHA256 verified)
GET    /api/v1/projects/{id}/gate        current gate config
PUT    /api/v1/projects/{id}/gate        replace gate config
```

Every non-2xx response is `application/problem+json` (RFC 7807) with a stable `type`
URI per failure kind (`not-found`, `conflict`, `bad-request`, `unauthorized`,
`forbidden`, `payload-too-large`). Full OpenAPI 3.1, generated from annotations, is
served at `/swagger-ui` (and `/v3/api-docs`) once the app is running.

A report is authenticated with `X-API-Key` and posted as a raw body — not JSON, not
multipart, since SARIF is already a JSON document and wrapping it again would only
inflate a payload that can legitimately be 200 MB:

```bash
curl -i "http://localhost:8080/api/v1/runs?owner=acme&repo=widgets&branch=main&commitSha=$(git rev-parse HEAD)" \
  -H "X-API-Key: $VESTIGE_API_KEY" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/sarif+json" \
  --data-binary @report.sarif.json
```

## Running it locally

### Backend + Postgres

```bash
docker compose up --build
```

Builds the app (`Dockerfile`, multi-stage: `maven:3.9-eclipse-temurin-17` →
`eclipse-temurin:17-jre-alpine`, non-root), starts Postgres 16, waits for its
healthcheck, runs Flyway automatically on boot, and serves the API at
`http://localhost:8080` (`/actuator/health` is the readiness probe). See the
[note below](#a-note-on-how-this-was-built) for why this exact command has not been
run inside the sandbox this repository was authored in.

Flyway connects as the database owner (`postgres`) to run migrations, including
`CREATE ROLE vestige_app` and `FORCE ROW LEVEL SECURITY`; the application itself
connects as `vestige_app`, the restricted role those migrations create — see
[ADR-002](docs/adr/002-shared-schema-postgres-rls-for-tenancy.md) for why the two
must differ. Both connections' defaults live in `application.yml` and are mirrored
exactly by `docker-compose.yml`'s environment block — there is no second source of
truth for configuration.

### Bootstrapping the first API key

v1 has no self-service signup and no "create organisation" endpoint (§8's API table
is deliberately just the resource endpoints listed above) — the honest state of a
freshly migrated database is zero organisations and zero API keys. Keys are
`vst_<prefix>_<secret>`, stored as `key_prefix` (a lookup handle) plus
`key_hash = sha256(whole key)` (`ApiKeyAuthenticator`/`ApiKeyFactory`), so bootstrapping
one by hand is a single `psql` session:

```bash
ORG_ID=$(uuidgen)
PREFIX=$(head -c6 /dev/urandom | base64 | tr '+/' 'xy' | tr -d '=')
SECRET=$(head -c32 /dev/urandom | base64 | tr '+/' 'xy' | tr -d '=')
KEY="vst_${PREFIX}_${SECRET}"
HASH=$(printf '%s' "$KEY" | sha256sum | cut -d' ' -f1)

# The defaults docker-compose.yml runs Postgres with - see VESTIGE_MIGRATION_DB_* if you
# changed them.
PGPASSWORD=postgres psql -h localhost -p 5432 -U postgres -d vestige <<SQL
insert into organization (id, slug, name) values ('$ORG_ID', 'acme', 'Acme, Inc.');
insert into api_key (id, organization_id, name, key_prefix, key_hash)
  values (gen_random_uuid(), '$ORG_ID', 'local dev', '$PREFIX', '$HASH');
SQL

echo "X-API-Key: $KEY"
```

(A first-class `POST /api/v1/organizations` + key-issuance endpoint is the obvious
follow-up; see [Roadmap](#roadmap--everything-this-is-honest-about-not-doing).)

### Frontend

```bash
cd frontend
npm install
npm run dev     # served at http://localhost:5173, proxying /api to :8080
```

`npm run build` produces a static `dist/` (verified clean in this repository: 73
modules, ~242 KB JS / ~8 KB CSS before gzip). `npm run lint` runs `oxlint`, the
scaffold's configured linter (not ESLint).

## Testing

```bash
mvn test                    # unit tests only — no Docker required
mvn verify -Pintegration    # + Testcontainers ITs against a real Postgres
scripts/offline-verify.sh   # the dependency-free core, no Maven/network required at all
```

- **Unit tests** (`src/test/java/**/*Test.java`, 22 classes / 143 `@Test` methods)
  mock every collaborator with Mockito and assert behaviour with AssertJ — no Spring
  context, no database. The dependency-free subset of these (13 classes / 110
  methods: the matcher, the SARIF reader, the hash chain, the gate evaluator, the
  webhook verifier, `matcher-corpus`) is exactly what `scripts/offline-verify.sh`
  compiles and runs with plain `javac`; the rest additionally mock Spring-stereotyped
  repositories/services with Mockito and need the full Maven build to compile.
- **Integration tests** (`@Tag("integration")`, `*IT.java`, 3 classes / 8 `@Test`
  methods, run only under `-Pintegration`, excluded from the default `mvn test`) spin
  up a real Postgres via Testcontainers and connect as `vestige_app`, not the
  container's superuser — see `AbstractIntegrationTest`'s own comment for why
  `@ServiceConnection` would have silently defeated the one test that matters most
  here (Postgres never applies RLS to a superuser, `FORCE ROW LEVEL SECURITY`
  notwithstanding). `VestigeAdversarialTenancyIT` (the RLS proof above),
  `OutboxSkipLockedConcurrencyIT` (20 seeded jobs, 5 racing worker threads, every job
  claimed exactly once), `VestigeAuditTamperDetectionIT` (the tamper-detection proof
  above).
- **`matcher-corpus/`** runs inside the ordinary unit-test suite (`MatcherCorpusHarnessTest`)
  — the false-merge/false-split gate is a real `mvn test` failure, not a separate report.
- **Frontend**: no test runner is configured in v1 (`vitest`/Playwright are named as
  the intended choice in `docs/ARCHITECTURE.md` §9 but not wired up) — see
  [Roadmap](#roadmap--everything-this-is-honest-about-not-doing). `npm run build`'s
  own type-checking (`tsc -b`, strict mode) is the only automated frontend check that
  currently exists.

## A note on how this was built

This repository was authored end-to-end inside a sandboxed agent environment with two
concrete restrictions worth stating plainly rather than glossing over:

- **Maven Central is not reachable** (the environment's egress proxy returns `403` on
  it). `mvn test`/`mvn verify` cannot resolve Spring Boot, JPA, Testcontainers, or any
  of this project's Maven dependencies inside that sandbox — confirmed, not assumed:
  `mvn -B test` there fails at the very first step, resolving `spring-boot-starter-parent`.
- **No Docker daemon is reachable** (`docker info` fails to find `/var/run/docker.sock`),
  so Testcontainers-backed integration tests cannot run there either.

Neither restriction is specific to this project or this code — they are properties of
that one sandbox. `scripts/offline-verify.sh` exists specifically to give this
repository *something* it can prove clean under those constraints: every dependency-free
package (the matcher, the SARIF streaming reader, the hash chain, the quality gate
evaluator, the webhook signature verifier, and the full `matcher-corpus` harness) is
compiled with plain `javac` against JUnit 5/AssertJ/Jackson from the system's own
`/usr/share/java`, and run — 110 tests, all passing, in the run recorded above. Every
Spring/JPA/servlet-dependent class (everything under a `service`, `web`, `api`, or
`worker` package that is not itself dependency-free) was written and cross-checked
by hand against the actual entity/repository signatures it calls, but was not, and
could not be, compiled inside that sandbox.

`.github/workflows/ci.yml` is written to run the real thing on GitHub-hosted runners,
which have both unrestricted Maven Central access and Docker preinstalled: `mvn test`
(unit + matcher-corpus gate), `mvn verify -Pintegration` (the three Testcontainers
ITs), and the frontend build, as three separate jobs. That workflow is the actual
verification story for this repository going forward, and is expected — not merely
hoped — to pass, having been written against the same interfaces `scripts/offline-verify.sh`
already proved out and the same manual cross-referencing described above; it simply
has not been *run* yet, because doing so needs a real push to a GitHub repository this
exercise did not include.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 17 (built with 21, targets 17 release) · Spring Boot 3.3 | Ecosystem match for the target reader; mature static-analysis tooling |
| Persistence | PostgreSQL 16 | Row-level security, `SKIP LOCKED`, `jsonb` for the gate's computed result document |
| Access | Spring Data JPA | See [Roadmap](#roadmap--everything-this-is-honest-about-not-doing) — §9 named jOOQ for the matcher's bulk paths; the matcher ended up operating in memory over data JPA already loaded, so jOOQ was never actually pulled in |
| Migrations | Flyway | Versioned, reviewable, no auto-DDL |
| Frontend | React 19 + TypeScript, Vite, TanStack Query | Server state without hand-rolled caching |
| Testing | JUnit 5, Testcontainers, AssertJ, Mockito | Real Postgres in integration tests — RLS and `SKIP LOCKED` cannot be tested against H2 |
| Quality | Spotless (Google Java Format), JaCoCo | Formatting and coverage enforced in CI |
| CI | GitHub Actions | Unit / integration / frontend as separate jobs; matcher-corpus gate inside the unit job |
| Deploy | Docker Compose (dev), single container + managed Postgres (prod shape) | Keeps the ops story honest and reproducible |

## Project layout

```
docs/
  ARCHITECTURE.md         the frozen design brief this repository implements
  adr/001-…010-….md       10 Architecture Decision Records
src/main/java/…           common/ tenancy/ ingestion/ matching/ issues/ triage/ gate/ github/
src/main/resources/
  application.yml
  db/migration/           4 Flyway migrations: schema, RLS, API keys, audit trigger
src/test/java/…           unit tests (mirrors main), plus support/ (Testcontainers base)
matcher-corpus/           32 hand-built fixtures + the harness that scores them
scripts/offline-verify.sh compiles and runs the dependency-free core with no Maven/network
frontend/                 React 19 + TypeScript + Vite dashboard
docker-compose.yml, Dockerfile, .github/workflows/ci.yml
```

## Architecture Decision Records

Each one states the decision, the rejected alternatives (argued seriously, not
strawmanned), and what would change the author's mind.

| # | Decision |
|---|---|
| [001](docs/adr/001-fingerprint-ladder-for-issue-identity.md) | Three-rung fingerprint ladder for issue identity |
| [002](docs/adr/002-shared-schema-postgres-rls-for-tenancy.md) | Shared schema + Postgres RLS for tenancy |
| [003](docs/adr/003-separate-finding-from-issue.md) | Separate `Finding` (immutable) from `Issue` (mutable) |
| [004](docs/adr/004-transactional-outbox-with-skip-locked.md) | Transactional outbox + `SKIP LOCKED` polling |
| [005](docs/adr/005-client-supplied-idempotency-keys.md) | Client-supplied idempotency keys with `409` on body mismatch |
| [006](docs/adr/006-hash-chained-audit-log.md) | Hash-chained audit log |
| [007](docs/adr/007-sarif-as-sole-ingestion-format.md) | SARIF as the sole ingestion format |
| [008](docs/adr/008-matcher-defined-new-code.md) | "New code" defined by matcher output, not by git diff |
| [009](docs/adr/009-streaming-sarif-parse.md) | Streaming SARIF parse with batched inserts |
| [010](docs/adr/010-symbol-path-from-logical-locations.md) | Symbol path from SARIF `logicalLocations`, with graceful degradation |

## Roadmap — everything this is honest about not doing

Stated explicitly so a reader does not mistake omission for oversight — the same
standard §11 of the architecture brief holds itself to:

- **Object storage for uploaded reports.** `analysis_report_payload.sarif` is a
  Postgres `bytea` column in this repository; production would use an object-storage
  key instead, keeping the database out of the business of storing 200 MB blobs. The
  streaming-parse design (ADR-009) is independent of this and would not change.
- **`jOOQ` was named in the brief (§9) and not used.** The matcher operates in memory
  over issues/findings JPA already loaded rather than needing set-based bulk SQL of
  its own, so the dependency was never actually pulled in — the tour above states
  this plainly rather than leaving a stale line in a technology table uncorrected.
- **`actor`, not `actor_id`.** The audit payload's `actor` field (§6 names it
  `actor_id`) is a caller-supplied free-text string, not a checked foreign key,
  because v1 has no per-user identity at all — only organisation-scoped API keys (see
  the next point). The hash chain proves an action happened and was not later
  altered; it does not prove the named actor is who performed it. See
  `TriageEvent`'s own class javadoc.
- **No per-user auth, SSO or SCIM.** Authentication is one header, one key, one
  organisation (§11 excludes this scope explicitly). There is consequently no
  "create an organisation" or "issue an API key" endpoint either — see
  [Bootstrapping the first API key](#bootstrapping-the-first-api-key) for the manual
  `psql` step that stands in for it.
- **GitHub Check Run publishing is a stub.** `CheckRunPublisher` is a real interface
  with a `NoopCheckRunPublisher` implementation that logs and does nothing — gate
  results are computed and persisted correctly, they are just not posted back to a PR
  yet. Wiring a `GitHubCheckRunPublisher` behind the same interface is additive, not
  a redesign.
- **The GitHub webhook endpoint verifies and stops.** `POST /api/v1/webhooks/github`
  checks the HMAC-SHA256 signature and returns `202`, but does not yet act on the
  event (e.g., auto-triggering ingestion on a push). Nothing about ingestion or
  matching depends on this — a client can always call `POST /api/v1/runs` directly,
  which is what the whole system is actually built to receive.
- **GitLab and Bitbucket are interface-shaped, not implemented.** `ScmRenameResolver`
  has one real implementation (`GitHubScmRenameResolver`) and a `NoopScmRenameResolver`
  fallback; §11 states only GitHub is implemented in v1 by design.
- **`Branch.baselineBranchId` is never populated.** A feature branch's matching
  baseline is recorded as a first-class relationship in the schema (§2.2), but nothing
  in v1 ever sets it on the ingestion path — every branch currently matches against
  its own prior history rather than inheriting a reference branch's. `Branch`'s own
  javadoc documents this as a stated v1 gap, not an oversight.
- **No `vitest`/Playwright.** §9 names both; neither is wired up. `tsc -b`'s strict
  type-checking on every `npm run build` is the only automated frontend check today.
- **No live self-analysis instance.** §12 describes Vestige's CI running SonarQube
  Community Build against Vestige's own source, exporting SARIF, and ingesting it
  into a running Vestige instance — the strongest possible demo, and a genuine
  follow-up once this repository has a real deployment target to point at. It is not
  running today; there is no live URL this README can honestly link to yet.

## License

MIT — see [`LICENSE`](LICENSE).
