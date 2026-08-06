# ADR-005: Client-supplied idempotency keys with `409` on body mismatch

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §4.1, ADR-004

## Context

CI retries failed requests. Webhooks and CI steps both routinely deliver the same
logical event more than once — a step that times out waiting for a response the
server actually sent, a pipeline re-run after an unrelated failure, a flaky network
hop. `POST /api/v1/runs` must therefore be safe to call twice for the same submission
without double-counting findings, opening duplicate issues, or corrupting an issue's
matched history — while still rejecting the case that is not a harmless retry: two
genuinely different reports arriving under the same key, which is a client bug and
should be surfaced as one rather than silently resolved in either report's favour.

## Decision

`POST /api/v1/runs` accepts an optional `Idempotency-Key` header. If the caller does
not send one, Vestige computes a natural key itself:
`sha256(project_id ‖ commit_sha ‖ analyser_name ‖ report_digest)`
(`Sha256.hexOfFields`, `RunIngestionService.submit`) — so a client that never thinks
about idempotency at all still gets its safety, derived entirely from facts about the
request that are already available before any processing happens.

A submission is resolved against **two** independent lookups, either of which counts
as "this was already submitted": the key match (`organization_id, idempotency_key`,
unique-indexed), and the natural key match
(`project_id, commit_sha, analyser_name, report_digest`) — the second lookup exists
because the same report can legitimately arrive a second time under a *different* or
*absent* client key from its original submission, and that should still resolve to
the original run rather than a raw unique-constraint violation.

- A match whose `(project, branch, commit, report_digest)` agrees with the incoming
  request is a true duplicate: return the **original** run's result, unprocessed
  again, `200`.
- A match under the same key whose stored request disagrees on any of those fields is
  a **conflict**: the key was reused for a different report, which is very likely a
  client bug (a hardcoded key, a cache collision) rather than a legitimate retry —
  return `409` rather than guess which of the two conflicting reports the caller
  actually meant.

Both checks, and the run's creation when neither matches, happen inside the same
transaction (`@Transactional` on `submit`) — there is no read-then-write gap for a
concurrent identical request to land in unnoticed. A genuinely concurrent pair of
identical submissions racing each other on their very first attempt can still lose to
the database's own unique index and surface a generic `409` to the loser rather than
this method's own friendly duplicate-detection path; that narrow, millisecond-wide
race is a documented, deliberate simplification (see `submit`'s own comment) rather
than an oversight — the losing client's correct reaction to any `409` is the same
"retry the identical request" it already does for other transient failures, and the
retry lands cleanly on the now-existing row.

## Rejected alternatives

**Server-side dedup on content hash alone** (no client key at all — a report is a
duplicate purely because `sha256(report_bytes)` matches one already seen). Simpler,
and was considered, but it silently accepts a real failure mode: the *same* analyser
run genuinely re-executed with a materially different result (a flaky rule,
non-deterministic ordering in the analyser itself) would look identical to a harmless
retry if the digest happened to still match by coincidence, and — worse in the other
direction — a client that *wants* strict idempotency (e.g., "I am retrying this exact
HTTP call, treat it as one submission no matter what") has no way to assert that
intent; content hashing conflates "this is the same bytes" with "this is the same
logical request," which are not always the same question. The client-supplied-key
design lets a caller who cares make the assertion explicit, while still providing the
content-hash-shaped fallback (the natural key) for callers who do not.

**Last-write-wins** (a repeat key simply reprocesses the report, overwriting whatever
the first attempt produced). Rejected outright: it means a retried request can
silently create a second, distinct `AnalysisRun`, double-count findings the matcher
has already opened issues for, and — worse — makes the system's behaviour depend on
timing (which of two concurrent identical requests happens to finish last), directly
violating §3.3's determinism requirement one layer up from the matcher itself. This
is the one alternative ADR-005 rejects without much hesitation: it does not just fail
to solve exactly-once effects, it actively breaks a guarantee a different part of the
system depends on.

## Consequences

- A well-behaved client that never sets `Idempotency-Key` still gets exactly-once
  behaviour for free, because the natural key is derived from the request itself — the
  feature does not require CI pipeline authors to know it exists.
- A client that *does* set the header gets a stronger guarantee: two requests it
  considers "the same logical submission" are treated as one even if, hypothetically,
  their SARIF bytes differed byte-for-byte (a re-export with different timestamps
  embedded, for instance) — the key is authoritative over content in that case, and a
  mismatch is reported as a conflict rather than silently picking one side.
- The `409`-on-mismatch path is a deliberate refusal to guess. A caller that hits it
  has a real bug to fix (a colliding key), and Vestige surfacing that clearly beats
  quietly accepting one of the two conflicting reports and hiding the other.

## What would change our mind

If real usage showed the millisecond-wide concurrent-identical-submission race
described above happening often enough to be a genuine client-experience problem
(rather than the rare event it is expected to be), a catch-and-re-read loop around
`submit` would close it — strictly additive machinery layered onto the same design,
not a reason to change the key scheme itself.
