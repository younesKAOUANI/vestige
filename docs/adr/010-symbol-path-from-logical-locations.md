# ADR-010: Symbol path from SARIF `logicalLocations`, with graceful degradation

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §3.2, ADR-001, ADR-007

## Context

Rung 1 of the fingerprint ladder (`identity_fp`, ADR-001) needs a `symbol_path` — the
enclosing declaration chain, e.g. `com.acme.PaymentService#issueRefund` — because it
is the one signal in the whole ladder that survives a finding's line moving *and* the
line itself being edited, as long as the enclosing method or class is not renamed.
Vestige has no compiler front end and is deliberately scoped not to have one (§11
excludes writing another analyser; ADR-007 commits to consuming SARIF rather than
source). The question is where `symbol_path` comes from, given that constraint.

## Decision

`symbol_path` is read directly from SARIF: the first
`logicalLocations[].fullyQualifiedName` on a result's primary location
(`RawFinding.symbolPath`'s own javadoc), populated by the analyser that produced the
report, not derived by Vestige from source. When a producer supplies it,
`FingerprintFactory.compute` uses it to build `identity_fp`. When a producer does not,
`identity_fp` is simply `null` — not a placeholder, not a weaker guess — and the
matcher's bucketing (§3.3) has nothing to bucket that finding under at rung 1, so it
falls through to rung 2 (`context_fp`) automatically, with no special-case branch
needed anywhere in `IssueMatcher` to make that happen: an absent fingerprint is just
an empty bucket. This is the "graceful degradation" this ADR is named for — a report
from a tool with weaker location metadata gets a real, useful answer at rung 2 or 3
rather than an error, a rejected upload, or a silently wrong rung-1 match built on a
guess.

## Rejected alternatives

**Language-specific AST parsing in Vestige itself** (fetch the flagged file's source
at the relevant commit, parse it with a real parser per language, and derive the
enclosing symbol from the resulting tree). Would give Vestige a `symbol_path` even
when the analyser's own SARIF omits `logicalLocations`, and was seriously considered
for exactly that reason. Rejected because it turns a service that ingests analysis
results into one that also *performs* a slice of analysis — parsing arbitrary source
in an open-ended set of languages, keeping each language's grammar current as the
languages evolve, and fetching source at arbitrary historical commits from whichever
SCM a project uses (a second data dependency `SarifReader` does not otherwise need at
all). §11 rules this out in plain language: "Vestige does not analyse code. It
consumes analyser output. Writing another linter is not the point." — and a
from-scratch multi-language AST layer is, in effect, writing a large part of one,
scoped to a single field this system needs, for a benefit only realised on the subset
of reports from analysers that omit `logicalLocations` in the first place. Most
production-grade analysers (SonarQube, CodeQL, and increasingly Semgrep) already
populate it, which further narrows how much this alternative would actually buy.

## Consequences

- Match quality for a given report is bounded by how much location metadata its
  producing analyser chooses to emit — a real, external dependency this design
  accepts rather than tries to compensate for internally. `matcher-corpus/` tests this
  directly: several fixture pairs are deliberately duplicated once with a symbol path
  and once without (e.g. `08-move-file-with-symbol-path` /
  `09-move-file-no-symbol-path`, `11-inline-variable-with-symbol-path` /
  `12-inline-variable-no-symbol-path`), and the harness's 0% false-merge / 0%
  false-split result holds across both — proof that degrading to rung 2 is a graceful
  fallback in practice, not just in theory.
- `LineNormalizer` (rung 2's basis) is consequently load-bearing in a way it would be
  merely convenient without this ADR's constraint: it is not just "the second-best
  signal," it is the signal every symbol-path-free analyser's findings actually rely
  on for anything beyond rung 3's coarse file-plus-proximity match. Its own javadoc
  states the AST rejection explicitly as the reason it exists as a character-level
  heuristic scanner rather than leaning on a language front end that does not exist
  in this system.
- No configuration or per-analyser special-casing is needed to support tools with
  different metadata richness — the same `FingerprintFactory.compute` call handles
  both cases uniformly, because `null` is a legitimate, first-class value for
  `identity_fp`, not an error state.

## What would change our mind

If a specific, widely-used analyser integration turned out to omit `logicalLocations`
*and* rung 2/3 measurably underperformed for that analyser's typical output shape on
the corpus, a narrow, single-language enrichment step (not a general AST layer) for
that one case would be a proportionate, additive response — evaluated against the
corpus the same way every matching change is, not against intuition about what a
compiler could theoretically provide.
