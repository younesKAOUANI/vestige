# Vestige — Architecture & System Study

> **Vestige** *(n.)* — a trace of something that once existed.
>
> A multi-tenant service that ingests static-analysis reports from CI, tracks the
> identity of each finding as the code beneath it changes, and produces an
> auditable record of every triage decision a team makes.

**Author:** Younes Kaouani · **Status:** design frozen for v1 · **Date:** August 2026

---

## 1. Why this project exists

Static analysers are good at answering *"what is wrong with this file, right now?"*
They are bad at answering the question teams actually care about:

> *"Is this a **new** problem, or the same one we already agreed to live with three
> months ago — and who agreed to it?"*

Answering that requires solving a problem the analyser itself does not solve:
**finding identity across time**. A finding is not a line number. Line numbers move
every time someone adds an import. A finding is a *claim about a piece of code*, and
that claim survives edits, renames, reformatting and refactors.

Vestige is the layer that owns that claim. It sits between CI and the developer:

```
  CI job → analyser (SonarQube / Semgrep / CodeQL) → SARIF → Vestige → dashboard
                                                                  ↓
                                                          quality gate verdict → back to the PR
```

### 1.1 Why this is worth building rather than another CRUD app

Three properties make it a genuine engineering problem rather than a form over a database:

1. **Identity matching is heuristic and adversarial.** There is no correct answer, only
   trade-offs between false merges (two distinct problems collapsed into one) and false
   splits (one problem resurfacing as "new" after a whitespace change). Both failure modes
   destroy user trust, and they pull in opposite directions.
2. **Ingestion must be exactly-once in effect.** CI retries. Webhooks are delivered at
   least once. A re-delivered report must not double-count findings or corrupt an issue's
   history.
3. **The audit trail is the product.** If a team can silently rewrite the record of who
   dismissed a security finding, the record is worthless. Tamper-evidence is a hard
   requirement, not a feature.

---

## 2. Domain model

### 2.1 The core distinction: Finding vs Issue

This is the single most important modelling decision in the system.

| Concept | Definition | Lifetime |
|---|---|---|
| **Finding** | One raw result from one analyser in one analysis run. Immutable. `(run, rule, file, line, message)`. | Forever, append-only |
| **Issue** | A *stable claim* that a defect exists, spanning many runs. Mutable status. | Opened once, may be resolved and reopened |

A Finding is an observation. An Issue is the thing a human argues about. One Issue
accumulates many Findings over its life — one per analysis run in which it was still
present.

```
Project ──< Branch ──< AnalysisRun ──< Finding >── Issue
                                                    │
                                                    └──< TriageEvent (hash-chained)
```

### 2.2 Entities

**Organisation** — tenant boundary. Every row in every tenant-scoped table carries
`organisation_id`. Enforced by Postgres RLS, not by application code (see §5.2).

**Project** — a repository under analysis. Holds provider coordinates
(`github|gitlab|bitbucket`, `owner`, `name`) and the quality-gate configuration.

**Branch** — analysis is always branch-scoped. `main` is the reference branch; feature
branches inherit their baseline from it. This mirrors how teams actually reason: *"does
my PR make things worse than main?"*

**AnalysisRun** — one execution of one analyser against one commit.
`(project, branch, commit_sha, analyser, run_key)`. `run_key` is the client-supplied
idempotency key (see §4.1). Carries a state machine:
`RECEIVED → PARSING → MATCHING → COMPLETE | FAILED | QUARANTINED`.

**Finding** — immutable. Parsed from SARIF. Carries the raw location, the rule id, the
severity, and the computed fingerprints (§3.2).

**Issue** — the tracked claim. Status:
`OPEN → RESOLVED_FIXED | RESOLVED_FALSE_POSITIVE | RESOLVED_WONT_FIX`, and back to
`REOPENED` if it reappears after being resolved. Carries `first_seen_run`,
`last_seen_run`, and `introduced_at_commit`.

**TriageEvent** — an append-only, hash-chained log of every human decision:
who, when, from-status, to-status, justification. See §6.

**QualityGate / QualityGateResult** — a set of conditions evaluated per run, producing
`PASSED | FAILED`, with the failing conditions enumerated.

---

## 3. The hard part: issue identity across commits

### 3.1 The problem, concretely

Run 1, commit `a1b2c3`, `PaymentService.java`:

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

Line 42 became line 58. The message text changed (`o` → `order`). Naïve matching on
`(rule, file, line)` reports the old issue as **fixed** and the new one as **newly
introduced**. The team's "new issues" count spikes, the quality gate fails a PR that
changed nothing relevant, and within two sprints everyone ignores the tool.

### 3.2 The approach: a fingerprint ladder

Vestige computes **three** fingerprints per finding, and matches in descending order of
strength. Each rung is tried in full before falling to the next.

| Rung | Fingerprint | Composition | Survives | Breaks on |
|---|---|---|---|---|
| 1 | `identity_fp` | `sha256(rule_id ‖ normalised_file_path ‖ symbol_path)` | line moves, reformatting, renamed locals | renamed enclosing method/class |
| 2 | `context_fp` | `sha256(rule_id ‖ normalised_file_path ‖ normalised_line_hash)` | line moves, comment edits, whitespace | edits to the flagged line itself |
| 3 | `weak_fp` | `sha256(rule_id ‖ normalised_file_path)` + line proximity ≤ 25 | almost everything in-file | file rename |

**`symbol_path`** — the enclosing declaration chain, e.g.
`com.acme.PaymentService#issueRefund`. Extracted from SARIF `logicalLocations` when the
analyser supplies it; when it does not, Vestige falls back to rung 2. This is the key
insight: analysers already know the enclosing symbol, and symbols move far less often
than lines.

**`normalised_line_hash`** — the flagged line, stripped of leading/trailing whitespace,
with all string and numeric literals replaced by `§`, then hashed. `x = "abc" + 1` and
`x = "def" + 2` hash identically. This deliberately tolerates the exact edit in §3.1.

**File rename handling.** Before matching, Vestige asks the SCM provider for the commit's
rename map (`GET /repos/{o}/{r}/compare/{base}...{head}` exposes `previous_filename`).
Renames are applied to the previous run's findings before comparison, so a pure `git mv`
does not orphan a single issue.

### 3.3 The matching algorithm

```
match(previousOpenIssues P, currentFindings C):
    apply rename map to P
    unmatched_P ← P ; unmatched_C ← C ; matches ← ∅

    for rung in [identity_fp, context_fp, weak_fp]:
        buckets ← group unmatched_P by rung fingerprint
        for c in unmatched_C:
            candidates ← buckets[fingerprint(c, rung)]
            if candidates is empty: continue
            if rung is weak_fp:
                candidates ← filter |line(candidate) − line(c)| ≤ 25
            best ← argmin over candidates of |line(candidate) − line(c)|      ← stable tie-break
            matches ← matches ∪ {(best, c)} ; remove both from unmatched
        # a rung completes fully before the next begins: strong evidence always wins

    for (p, c) in matches:       p.lastSeen ← run ; attach c to p
    for c in unmatched_C:        open new Issue (introduced at this commit)
    for p in unmatched_P:        p.status ← RESOLVED_FIXED (auto)
```

**Complexity.** Bucketing by fingerprint makes each rung O(|P| + |C|) rather than the
naïve O(|P| × |C|). On a 50k-finding repository that is the difference between
milliseconds and minutes.

**Determinism.** The tie-break is line proximity, then lowest finding id. The same two
inputs always produce the same matching — required, because the matcher re-runs on
replay (§4.2) and must not produce a different history.

### 3.4 How this gets validated

A heuristic without a measurement is a guess. `matcher-corpus/` holds ~40 hand-labelled
before/after pairs, each a real refactor shape (extract method, rename symbol, reorder
imports, reformat, move file, inline variable, wrap in try/catch), with the *expected*
matching recorded as ground truth. CI asserts:

- **false-split rate** ≤ 5% — issues wrongly reported as new
- **false-merge rate** = 0% — two distinct issues wrongly collapsed (this must never happen)

Tuning the ladder without moving these numbers is the whole point. The corpus is the
project's most valuable artefact, and the README says so.

---

## 4. Ingestion: exactly-once effects

### 4.1 Idempotency

CI retries. Webhooks are delivered at least once, sometimes many times. The contract:

```
POST /api/v1/runs
Idempotency-Key: <client-supplied, or sha256(project ‖ commit ‖ analyser ‖ report_digest)>
```

- The key is stored `UNIQUE` alongside the run.
- A repeat key returns `200` with the **original** run's result and does not re-process.
- A repeat key with a *different* request body returns `409 Conflict` — this catches
  client bugs rather than silently accepting one of two conflicting reports.

Ingestion runs inside a single transaction per run. The run row, its findings, the issue
mutations and the gate result commit together or not at all. There is no window in which
a run is half-applied.

### 4.2 The processing pipeline

An outbox-driven worker, not a message broker. Rationale in ADR-004.

```
POST /runs → validate SARIF → persist run (RECEIVED) + outbox row → 202 Accepted
                                          ↓ (worker polls with SKIP LOCKED)
                                   PARSING → MATCHING → gate eval → COMPLETE
```

- `FOR UPDATE SKIP LOCKED` gives safe concurrent workers with no extra infrastructure.
- Retries use exponential backoff with full jitter: `min(2^n · 250ms, 5min) · U(0,1)`.
- **Poison messages**: after 5 attempts the run moves to `QUARANTINED`, never retried
  automatically, and surfaced in the UI with the captured stack trace. A stuck run must
  never be able to block the queue or silently vanish. Requeue is a manual, audited action.

### 4.3 Large reports

SARIF files from a monorepo scan reach hundreds of megabytes. Vestige streams them with
a Jackson pull parser rather than binding to an object graph, and inserts findings in
batches of 1,000. Peak heap stays flat regardless of report size. The upload endpoint
enforces a configurable ceiling (default 200 MB) and rejects with `413` above it.

---

## 5. Multi-tenancy

### 5.1 Model

Shared schema, shared tables, row-level isolation keyed on `organisation_id`. Rejected
alternatives — schema-per-tenant, database-per-tenant — are argued in ADR-002.

### 5.2 Enforcement is in the database, not the application

Every tenant-scoped table has RLS enabled:

```sql
ALTER TABLE issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE issue FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON issue
  USING (organisation_id = current_setting('vestige.current_org')::uuid);
```

A Spring `AbstractRoutingDataSource` wrapper issues
`SET LOCAL vestige.current_org = ?` at the start of every transaction, from the
authenticated principal. `FORCE ROW LEVEL SECURITY` means even the table owner is
subject to the policy.

**Why this matters, stated plainly in the README:** application-layer tenancy filters
are one forgotten `WHERE` clause away from a cross-tenant data leak. Pushing the
predicate into the database makes the leak structurally impossible rather than
conventionally unlikely. The test suite includes a deliberate adversarial case: a
repository method with the tenant filter *removed* must still return zero foreign rows.

---

## 6. Tamper-evident triage log

Every status change on an Issue appends a `TriageEvent`:

```
prev_hash ← hash of the previous event for this organisation (genesis = 32 zero bytes)
payload   ← canonical JSON {issue_id, actor_id, from_status, to_status, justification, occurred_at}
hash      ← sha256(prev_hash ‖ sha256(payload))
```

- Append-only, enforced by a `BEFORE UPDATE OR DELETE` trigger that raises an exception.
- `GET /api/v1/audit/verify` walks the chain and returns the first index at which it
  breaks, or `{"intact": true, "length": n}`.
- A deleted or edited row breaks every subsequent hash — detectable, not preventable,
  which is the honest guarantee and is documented as such.

This is deliberately **not** a blockchain, and the README says why: there is no
distributed trust problem here, only a "prove the record was not quietly edited"
problem, and a hash chain solves exactly that at a fraction of the complexity.

---

## 7. Quality gates

A gate is a set of conditions evaluated against a run:

| Condition | Scope | Example |
|---|---|---|
| `NEW_CRITICAL_ISSUES` | new code | `= 0` |
| `NEW_ISSUES_TOTAL` | new code | `≤ 5` |
| `REOPENED_ISSUES` | new code | `= 0` |
| `TOTAL_BLOCKER_ISSUES` | overall | `= 0` |

**"New code" is defined by the matcher, not by the diff.** An issue is new if the matcher
opened it in this run. That is the whole reason §3 has to be right: the gate is only as
trustworthy as the identity matching underneath it. The README makes this dependency
explicit, because it is the system's central engineering argument.

Results post back to the PR as a GitHub Check Run (conclusion + annotations on the
offending lines).

---

## 8. API surface

```
POST   /api/v1/runs                      ingest a SARIF report (idempotent)
GET    /api/v1/runs/{id}                 run status + gate result
GET    /api/v1/projects/{id}/issues      filter: status, severity, rule, since-run
PATCH  /api/v1/issues/{id}               triage — requires justification on FALSE_POSITIVE/WONT_FIX
GET    /api/v1/issues/{id}/history       full finding + triage timeline
GET    /api/v1/audit/verify              hash-chain integrity check
POST   /api/v1/webhooks/github           push + PR events (HMAC-SHA256 verified)
GET    /api/v1/projects/{id}/gate        current gate config
PUT    /api/v1/projects/{id}/gate        update gate config
```

OpenAPI 3.1 generated from annotations, served at `/swagger-ui`.

---

## 9. Technology choices

| Layer | Choice | Reason |
|---|---|---|
| Language | **Java 17**, Spring Boot 3.3 | Strong static-analysis tooling, and the ecosystem most SARIF-emitting analysers are written against |
| Persistence | **PostgreSQL 16** | RLS, `SKIP LOCKED`, JSONB for raw SARIF payloads, generated columns |
| Access | Spring Data JPA + jOOQ for the matcher's bulk paths | JPA for CRUD clarity; jOOQ where set-based SQL wins |
| Migrations | Flyway | Versioned, reviewable, no auto-DDL |
| Frontend | **React 19 + TypeScript**, Vite, TanStack Query | Matches existing strengths; server state without hand-rolled caching |
| Testing | JUnit 5, **Testcontainers**, REST Assured, Vitest, Playwright | Real Postgres in tests — RLS and `SKIP LOCKED` cannot be tested against H2 |
| Quality | SonarQube Community, JaCoCo, Spotless, ArchUnit | The project analyses itself; results are ingested into Vestige (§12) |
| CI | GitHub Actions | Matrix build, Testcontainers-backed integration tests, matcher-corpus gate |
| Deploy | Docker Compose (dev), single container + managed Postgres (prod) | Keep the ops story honest and reproducible |

---

## 10. Architecture Decision Records

Each ADR states the decision, the rejected options, and — critically — **what would make
us change our mind**. Rejected options are argued seriously, not strawmanned.

| # | Decision | Rejected |
|---|---|---|
| 001 | Three-rung fingerprint ladder for issue identity | Line-number matching · pure diff-based tracking · embedding similarity |
| 002 | Shared schema + Postgres RLS for tenancy | Schema-per-tenant · database-per-tenant · app-layer filtering |
| 003 | Separate `Finding` (immutable) from `Issue` (mutable) | Single mutable table with a version column |
| 004 | Transactional outbox + `SKIP LOCKED` polling | Kafka · RabbitMQ · Spring `@Async` · pg_cron |
| 005 | Client-supplied idempotency keys with `409` on body mismatch | Server-side dedup on content hash alone · last-write-wins |
| 006 | Hash-chained audit log | Append-only table without chaining · event sourcing throughout · external ledger |
| 007 | SARIF as the sole ingestion format | Per-analyser native parsers · a bespoke JSON schema |
| 008 | "New code" defined by matcher output, not by git diff | Diff-based new-code detection · date-based leak period |
| 009 | Streaming SARIF parse with batched inserts | Full object binding · external object storage staging |
| 010 | Symbol path from SARIF `logicalLocations`, with graceful degradation | Language-specific AST parsing in Vestige itself |

---

## 11. Non-goals (v1)

Stated explicitly so reviewers do not mistake omission for oversight:

- Vestige does **not** analyse code. It consumes analyser output. Writing another linter
  is not the point.
- No IDE plugin, no self-hosted runner, no SSO/SCIM.
- No cross-project issue correlation.
- GitLab and Bitbucket adapters are interface-shaped but only GitHub is implemented.

---

## 12. The self-referential proof

Vestige's own CI runs SonarQube Community Build against Vestige, exports SARIF, and
ingests it into a live Vestige instance. The README links to that instance.

This is the demo: **the tool tracking its own defects over its own history.** It proves
the ingestion path, the matcher, and the gate against real, messy, non-synthetic data —
and it is a far stronger artefact than a seeded demo database.

---

## 13. Build plan

| Phase | Deliverable | Definition of done |
|---|---|---|
| 0 | Skeleton, Compose, Flyway, CI | `docker compose up` serves `/actuator/health` |
| 1 | Domain + RLS + adversarial tenancy test | Foreign rows unreachable even with the app filter removed |
| 2 | SARIF streaming parser | 200 MB fixture parses under a fixed heap ceiling |
| 3 | Ingestion + idempotency + outbox worker | Duplicate key returns original; conflicting body returns 409 |
| 4 | **The matcher + corpus harness** | Corpus green: false-merge 0%, false-split ≤ 5% |
| 5 | Triage + hash chain + verify endpoint | Tampering detected at the correct index |
| 6 | Quality gates + GitHub Check Run | Gate verdict appears on a real PR |
| 7 | React dashboard | Issue list, timeline, gate status, audit verification |
| 8 | Self-analysis loop + README | Vestige ingesting Vestige, publicly visible |

---

## 14. What a reviewer should take away

Read in order, this repository is meant to answer four questions:

1. **Can he identify the actually-hard problem?** — §3 exists, and the CRUD around it is
   treated as scaffolding.
2. **Can he reason about correctness under retry and concurrency?** — §4, and the poison-message
   path that does not lose data.
3. **Can he make a security property structural rather than conventional?** — §5.2 and its
   adversarial test.
4. **Can he write down why, including what he rejected?** — the ADRs, each with a
   change-our-mind clause.
