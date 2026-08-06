# ADR-007: SARIF as the sole ingestion format

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §11, ADR-009, ADR-010

## Context

Vestige has to accept results from more than one kind of analyser — the whole premise
is tracking findings across whatever tools a team already runs, not building another
linter (§11: "Vestige does not analyse code. It consumes analyser output."). Every
analyser worth integrating with (SonarQube, Semgrep, CodeQL, ESLint via a formatter,
and most others in active use) already has a way to emit results, and the question is
whether Vestige should understand each tool's native output shape directly, or commit
to a single common format and let tools that don't speak it natively translate on
their own side.

## Decision

Vestige ingests SARIF 2.1.0 exclusively — one parser (`SarifReader`), one schema to
reason about, one place fingerprinting logic lives. `RawFinding` is deliberately
shaped to be an almost direct write into the `finding` table: rule id, resolved
severity, message, location, and the fingerprints computed once at parse time — there
is no intermediate "generic finding" abstraction sitting between SARIF and the
database, because SARIF already *is* that abstraction. Severity resolves from the
result's own `level`, falling back to its rule's `defaultConfiguration.level`, falling
back to the GitHub-specific `security-severity` property — a preference order that
exists because real-world SARIF from different tools populates these fields
inconsistently, which is itself evidence for committing to one well-understood format
rather than several loosely-understood ones.

## Rejected alternatives

**Per-analyser native parsers** (a `SonarQubeReader`, a `SemgrepReader`, a
`CodeQLReader`, each understanding its tool's own output shape directly). Rejected
because it multiplies the surface area this project has to get right by however many
tools it wants to support, for a benefit that does not actually exist: nearly every
analyser Vestige would plausibly integrate with already emits SARIF as a first-class
or near-first-class output option, specifically because SARIF exists to solve this
exact interoperability problem industry-wide. Writing native parsers would mean
re-solving, per tool, a translation problem the tools' own maintainers have already
solved once, generically, and would move the fingerprinting logic (§3.2) — the part
of this system that actually matters — into N places that all have to stay
consistent with each other instead of one.

**A bespoke JSON schema** (Vestige defines its own finding format; a thin adapter
layer, maintained by Vestige or by integrators, converts SARIF and everything else
into it). Looks appealing as a way to decouple Vestige's internal model from a format
it does not control, but it is complexity solving a problem SARIF already solved:
SARIF 2.1.0 is an OASIS standard specifically designed to be the common interchange
format for static analysis results, and a bespoke schema would need to converge on
something isomorphic to it anyway to represent the same information (`ruleId`,
locations, `logicalLocations`, severity, rule metadata) — at which point Vestige would
be maintaining a schema and a translation layer for no expressive gain over accepting
SARIF directly, plus asking every integrator to either adopt Vestige's bespoke shape
or write their own adapter into it, which is strictly more integration work for
everyone than "point your existing SARIF output at this URL."

## Consequences

- A tool with no SARIF output at all cannot be integrated without an external
  conversion step (several exist as separate open-source projects for exactly this —
  converting a tool's native report into SARIF — which is the appropriate place for
  that translation to live, not inside Vestige).
- `SarifReader` has to be permissive about which fields a real-world SARIF document
  actually populates (severity resolution's three-way fallback is one example,
  `symbolPath`'s graceful absence — ADR-010 — is another) rather than assuming every
  producer fills in the specification's full optional surface consistently.
- Only the first `run` in a multi-run SARIF document is read; SARIF's multi-run
  reports (one document describing several tool executions) are out of scope for v1,
  a deliberate, narrow simplification rather than an oversight, since a CI pipeline
  invoking several analysers can equally well submit one report per analyser per
  `POST /api/v1/runs` call.

## What would change our mind

A specific, real analyser that a real user needs and that has no SARIF output and no
available third-party converter would be a concrete reason to reconsider — but the
right response even then would likely be contributing to or writing a small
SARIF-conversion adapter as its own tool, keeping this decision's boundary intact,
rather than teaching Vestige a second native format.
