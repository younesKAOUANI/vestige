# ADR-006: Hash-chained audit log

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §6, `V4__triage_event_append_only.sql`

## Context

A triage decision — "this is a false positive," "we accept this risk," "this is
fixed" — is a claim someone made about a piece of code, and §1.1 states plainly that
the audit trail recording those claims *is the product*: if a team can silently
rewrite who dismissed a security finding and when, the record is worthless to anyone
who later has to trust it — an auditor, a new team lead, an incident post-mortem six
months on. The requirement is not "log triage actions" (any table does that); it is
"make it detectable, after the fact, if a row in that log was altered or removed,"
including by someone with direct database access — an operator error, a compromised
application credential, or a well-intentioned "just this once" manual fix are all the
same threat from the log's point of view.

## Decision

Every triage decision appends a `TriageEvent` computed as:

```
prev_hash ← the chain's current tail for this organisation (genesis = 64 zero hex chars)
payload   ← canonical JSON {issueId, actor, fromStatus, toStatus, justification, occurredAt}
entry_hash ← sha256(prev_hash || sha256(canonical_json(payload)))
```

(`HashChain.entryHash`, `CanonicalJson` — a deterministic RFC 8785-shaped
serialisation so the same six fields always hash to the same pre-image regardless of
Java `Map` iteration order). Because `prev_hash` is folded into every entry's own
hash, altering or deleting any historical row invalidates that row's hash *and* every
hash after it — the verifier (`GET /api/v1/audit/verify`, `AuditChainVerifier`) walks
the whole chain, recomputes each hash from the stored payload, and reports either
`{"intact": true, "length": n}` or the exact zero-based index of the first row that no
longer verifies.

The chain is per-organisation, not per-issue (`AuditChainHead`, one row per tenant,
locked with `SELECT ... FOR UPDATE` by `TriageEventAppender` before computing the next
entry) — chaining across every issue an organisation has means tampering with *any*
one triage decision anywhere in the organisation's history is detectable from that one
per-tenant tail, not just tampering with the specific issue someone thought to check.

Immutability is enforced twice, deliberately redundantly: `TriageEvent` never exposes
a setter (an application-layer discipline), and — the enforcement that actually
matters — a `BEFORE UPDATE OR DELETE` trigger on `triage_event` raises an exception
on any attempt to modify or remove a row, unconditionally, for every role including
the application's own (`vestige_forbid_triage_event_mutation`, `V4`). The application
layer's own discipline is worth having, but an attacker with a raw SQL client bypasses
it entirely; the trigger is what does not need to be remembered by whoever writes the
next service class that touches this table, and it is the piece that makes the
guarantee **prevention**, not just detection — the integration test suite proves this
distinction directly (`VestigeAuditTamperDetectionIT`: a raw `UPDATE` and a raw
`DELETE` against `triage_event` both throw and leave the chain unchanged; only a
fabricated `INSERT` — which the trigger does not guard, by design, since appending is
the one legitimate operation — gets through to the database, and *that* is what the
verifier then catches).

## Rejected alternatives

**An append-only table without chaining** (the trigger alone, no `prev_hash`/
`entry_hash` at all). Prevents `UPDATE`/`DELETE` just as effectively — the trigger
does not care whether the row it is protecting is chained — but buys nothing against
the one thing chaining actually defends: a **row substitution**, where an attacker
with enough access to bypass the trigger's protection at the storage layer (a restore
from a doctored backup, a direct edit to the underlying files, a superuser who is
exempt from ordinary permission checks) replaces one row's content wholesale. An
unchained append-only table cannot tell that row's content is wrong; a chained one
can, because every later row's hash still commits to the *original* content. Chaining
is what turns "we prevent the ordinary case" into "we can also prove the extraordinary
one didn't happen."

**Event sourcing throughout** (the entire system's state derived by replaying an
event log, triage included). A genuinely bigger architectural commitment than this
problem calls for: it would mean every entity in the system — `Issue`, `AnalysisRun`,
`Project` — is a projection rather than a row, with all the query-model /
write-model plumbing that implies, to solve a problem that is actually scoped to one
table. §6's requirement is specifically about triage decisions being tamper-evident,
not about the system's entire persistence model; reaching for event sourcing here
would be solving a much larger problem than the one that was asked, for a benefit
this project does not need anywhere else.

**An external ledger** (write triage events to a separate tamper-evident store — a
managed ledger database, or a blockchain). ARCHITECTURE.md §6 addresses this
directly: this is deliberately *not* a blockchain, because there is no distributed
trust problem here — no mutually distrusting parties who need to agree on order
without a central authority. The actual problem is narrower: "prove this organisation's
own record was not quietly edited after the fact," which a hash chain inside the same
database solves completely, at a fraction of the operational and conceptual
complexity of standing up and trusting a second system.

## Consequences

- The guarantee is honestly stated as **detection**, not **prevention of data loss**:
  if a row genuinely is altered outside the trigger's reach, the chain proves it broke
  and names the index, but it cannot undo the alteration or reconstruct the original
  content by itself. The one correction path for a wrong triage decision is a new
  `TriageEvent` that supersedes it — preserving the record rather than erasing it —
  which is the same append-only discipline applied to fixing mistakes as to making
  them.
- `actor` is a caller-supplied free-text string, not a verified reference to a user
  (`TriageEvent`'s own class javadoc explains why: v1 has no per-user identity, only
  organisation-scoped API keys, §11). The chain proves an action happened and was not
  later altered; it does not by itself prove the named actor is who really performed
  it. That is a real, stated limitation — see this repository's README "Roadmap" —
  not an implied guarantee the field name would otherwise suggest.
- Verification is O(chain length) — it re-hashes every entry from genesis on every
  call. That is the right trade for v1: `GET /api/v1/audit/verify` is an
  on-demand integrity check, not a hot path, and a full walk is the only way to state
  the guarantee ("no tampering anywhere in the history") rather than a weaker one
  ("no tampering since the last time someone happened to check").

## What would change our mind

If chain length ever made a full walk too slow for an interactive "verify" button (at
a scale well beyond what any v1 tenant is expected to reach), a periodically-recorded
checkpoint hash — itself just another hash-chained fact, published somewhere outside
the database the way a blockchain's whole design is built around — would let
verification resume from the last trusted checkpoint instead of genesis. That is
additive on top of this design, not a reason to abandon hash chaining for something
else.
