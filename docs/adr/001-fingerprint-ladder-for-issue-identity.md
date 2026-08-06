# ADR-001: Three-rung fingerprint ladder for issue identity

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §3, ADR-008, ADR-010

## Context

A static analyser reports a finding at `(rule, file, line)`. None of those three
survive a normal week of development: lines move when anyone edits above them,
files move on a `git mv`, and even the rule id is the only field that never changes.
Naïve matching on the raw location therefore reports a fixed issue as "gone" and its
edited reappearance as "new" for the most ordinary of reasons — someone added an
import, renamed a variable, or ran a formatter. ARCHITECTURE.md §3.1 walks through
exactly this failure with a worked example (`refund` → `issueRefund`, twelve lines of
imports added, the flagged line's variable renamed): naïve matching reports the old
issue fixed and a new one opened, the gate fails a PR that changed nothing relevant,
and — as with any tool that cries wolf — the team stops trusting it within a couple of
sprints.

Vestige needs a way to decide, for every finding in a new run, "is this the same claim
a human already looked at, or a genuinely new one?" — cheaply enough to run on every
CI push, and correctly enough that a wrong answer in either direction is rare and,
in the security-relevant direction, effectively never.

## Decision

Compute three fingerprints per finding, ordered by how much they tolerate:

| Rung | Composition | Survives | Breaks on |
|---|---|---|---|
| 1 `identity_fp` | `sha256(rule_id ‖ normalised_file_path ‖ symbol_path)` | line moves, reformatting, renamed locals | renamed enclosing method/class |
| 2 `context_fp` | `sha256(rule_id ‖ normalised_file_path ‖ normalised_line_hash)` | line moves, comment edits, whitespace | edits to the flagged line itself |
| 3 `weak_fp` + proximity | `sha256(rule_id ‖ normalised_file_path)`, line distance ≤ 25 | almost everything in-file | file rename without resolution |

Matching runs rung 1 to completion across *every* unmatched finding before rung 2
starts, and rung 2 to completion before rung 3 starts — strong evidence always wins,
and a rung never "steals" a match that a stronger rung would have made correctly a
moment later. Within a rung, findings are bucketed by fingerprint value
(`IssueMatcher`), which makes each rung O(|previous| + |current|) instead of the
naïve O(|previous| × |current|) that a nested-loop comparison would cost — the
difference between milliseconds and minutes on a 50k-finding repository. The
tie-break within a bucket is deterministic: nearest line, then lowest finding id
(`Finding.seq`, a real bigint identity column added to the schema for exactly this —
see V1's comment on why a UUID alone cannot serve as a total order). Determinism is
not a nicety here: §3.3 requires the matcher to produce the same result on a replay
of the same two finding sets, and a tie-break that could differ between two runs of
the identical input would break that.

## Rejected alternatives

**Line-number matching (`rule_id, file, line`).** The status quo this ADR replaces,
and the one every analyser ships by default. Rejected because it is the literal
failure case in §3.1 — it is not a worse version of a good idea, it is the absence of
one.

**Pure diff-based tracking** (follow the flagged line through a computed unified
diff's hunks). More accurate than line numbers for small textual moves within a
file, but it does not generalise: SARIF carries no diff, so Vestige would need to
fetch and parse full source diffs per run from the SCM, which is an expensive
per-file operation at repository scale and still cannot follow a symbol across a file
rename without a second, separate rename-resolution step (which Vestige needs
anyway — see `ScmRenameResolver` — so diff-tracking would not even remove that
dependency). More fundamentally, tracking by diff hunk conflates "this code moved"
with "this code is new," and §7's quality gate needs those to be different questions:
new-code scope must be a property of what the *matcher* opened, not of which lines a
diff touched (ADR-008 argues this at length — it is the more consequential half of
this same decision).

**Embedding similarity** (embed the flagged code region, match by nearest neighbour).
Attractive on paper for tolerating semantic-preserving rewrites the fingerprint ladder
cannot (e.g. `if (a && b)` reordered to `if (b && a)`), but rejected on three grounds:
no determinism guarantee — a model upgrade could silently reshuffle historical matches
on redeploy, which §3.3 forbids outright; a live model dependency at ingestion time
(latency, cost, a pinned-version supply chain problem well beyond this project's
scope); and no natural evidence tiering — a cosine-similarity score is a single float
with no principled cutoff, where "which rung matched" gives a reviewer three
readable tiers (`Finding.matchRung`, surfaced in the UI) with a stated guarantee
attached to each one.

## Consequences

- The ladder is only as good as the signal it is fed. `symbol_path` (rung 1) depends
  on the analyser populating SARIF `logicalLocations`; when it does not, matching
  degrades to rung 2 automatically rather than failing (ADR-010).
- `matcher-corpus/` (32 hand-built before/after fixtures, each one real refactor
  shape: extract method, rename symbol, reorder imports, reformat, move file, inline
  variable, wrap in try/catch, and combinations of these) is what makes this more than
  an assertion. As measured by `MatcherCorpusHarness` (reproducible with
  `scripts/offline-verify.sh`, no network required): **0% false-merge, 0% false-split**
  across all 32 cases — comfortably inside §3.4's ≤5% false-split budget, and meeting
  its zero-tolerance false-merge bar exactly, not approximately.
- False-merge is treated as the worse failure mode throughout the matcher's design
  (a real regression silently absorbed into a "known, already-triaged" issue is a
  false sense of security), which is why rung 1 and 2 require an exact fingerprint
  match and only rung 3 tolerates any fuzziness at all, bounded to a 25-line window.

## What would change our mind

If a pathological refactor shape overwhelms rung 3's line-proximity heuristic and the
corpus's false-split rate cannot be driven under 5% by tuning the existing three
signals (e.g. widening the proximity window, or adding a fourth purely
syntactic signal), a structural signal — a lightweight per-language AST fingerprint —
would be worth its added complexity. That is deliberately not built pre-emptively:
ADR-010 argues the same point from the symbol-path side, and the corpus is the thing
that would have to demonstrate the ladder's actual ceiling before this ADR is revisited.
