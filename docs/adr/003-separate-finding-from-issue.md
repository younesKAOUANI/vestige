# ADR-003: Separate `Finding` (immutable) from `Issue` (mutable)

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §2.1

## Context

Every analysis run produces raw results. Across runs, some of those results are "the
same problem, seen again"; a human can resolve, silence, or reopen that problem; and
the system needs to answer both "what did run #482 actually report" (an audit
question, answered once and never revised) and "what is the current status of the
SQL-injection problem in `PaymentService`" (a triage question, whose answer changes
over the problem's lifetime) — without either question corrupting the other's
history.

## Decision

Two entities, not one:

- **`Finding`** — one raw result from one analyser in one run:
  `(run, rule, file, line, message, fingerprints)`. Written once when a run is parsed,
  never updated after. `finding.issue_id` is set exactly once, by the matcher, before
  the run's transaction commits (`IssueTrackingService`), and never revised — which is
  what "immutable" means for a Finding in practice: *which* claim it supports is
  decided once and never replayed.
- **`Issue`** — the mutable, cross-run claim a Finding is matched into:
  `status ∈ {OPEN, RESOLVED_FIXED, RESOLVED_FALSE_POSITIVE, RESOLVED_WONT_FIX,
  REOPENED}`, `first_seen_run_id`, `last_seen_run_id`. One Issue accumulates many
  Findings over its life, one per run in which the matcher decided it was still
  present.

`issue.rule_id` / `file_path` / `symbol_path` / `start_line` always reflect the *most
recent* sighting, not the first — matching always compares the current run's findings
against the previous run's open issues (§3.3), so an issue's displayed location is
whatever the matcher most recently confirmed it at, while the full history of exactly
where and how it was seen at every point in between lives in its `Finding` rows,
queryable through `finding.issue_id` and exposed via
`GET /api/v1/issues/{id}/history`.

## Rejected alternatives

**Single mutable table with a version column** (one `issue` row, its location fields
updated in place on every run, an incrementing `version` for optimistic locking, and a
separate history/audit table if anyone later needs to know what changed). This is a
smaller schema and it was seriously considered. Rejected for three concrete reasons:

1. **It cannot represent "the same issue was seen twice at different confidence"
   without inventing exactly the second table this ADR proposes anyway** — the moment
   you need "which rung matched on run N" per sighting (`Finding.matchRung`, surfaced
   in the UI so a reviewer can see *why* two runs were considered the same issue),
   you are storing a Finding-shaped row per run regardless of what the "main" table
   looks like. The single-table design does not remove that need, it just delays
   naming it.
2. **It conflates two very different write patterns.** A Finding is written once per
   run in a tight, high-volume batch insert (§4.3: streaming parse, batches of 1,000)
   and never touched again. An Issue is written rarely and read constantly — filtered,
   paginated, joined against triage events. Mixing both patterns onto one table means
   either the batch insert path is doing wasted `UPDATE`-style work on a row that also
   needs to support cheap point reads, or the read path pays for a table shaped around
   bulk-insert concerns it does not have.
3. **It makes "immutable" a promise instead of a property.** A single evolving row
   backed by a version column is only as append-only as the code that touches it
   remembers to be; nothing stops a future bug from overwriting a Finding's original
   `line_snippet` in place. Two tables make the immutability of a Finding a structural
   fact of the schema — there is no `UPDATE finding` code path in the system at all —
   the same argument ADR-002 makes for tenancy, applied to write discipline instead of
   read isolation.

## Consequences

- Every run's Finding rows are a permanent, append-only ledger; `finding` grows
  without bound relative to run volume, by design, and needs the indexes it has
  (`finding_run_idx`, `finding_issue_idx`) to stay queryable as it does. Retention/
  archival of old Finding rows is out of scope for v1 and is a fair follow-up once
  real data volume exists to size it against.
- `Issue` needing to reflect the *latest* sighting's location (not the first) means a
  reader cannot assume `issue.start_line` matches `issue.first_seen_run_id`'s
  Finding — that pairing has to be looked up through the history endpoint, which is
  documented on `issue`'s own schema comment so it is not a surprise the first time
  someone reads two fields off the same row and expects them to agree.
- The matcher only ever *reads* previously-open issues and *writes* new Finding rows
  plus at most one `issue.status`/location update per issue per run — it never
  rewrites Finding history, which keeps `IssueTrackingService`'s transaction small
  and its failure modes easy to reason about (either the whole run's matching commits,
  or none of it does).

## What would change our mind

If Finding volume ever made the append-only ledger operationally expensive before any
retention policy existed for it, that would argue for a retention/archival strategy,
not for merging the two tables back together — the two write patterns described above
do not change shape just because the table is large.
