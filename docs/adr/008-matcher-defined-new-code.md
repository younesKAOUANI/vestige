# ADR-008: "New code" defined by matcher output, not by git diff

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §7, ADR-001

## Context

A quality gate that fails on *every* pre-existing issue in a repository is useless the
day it is turned on — a codebase with a backlog of known, accepted debt would fail its
very first PR for reasons that PR did not cause, and the team would disable the gate
before lunch. Gates have to be scoped to **new** problems: whatever a change actually
introduced, not the debt that predates it. The question this ADR answers is what "new"
means, precisely enough that `NEW_CRITICAL_ISSUES`, `NEW_ISSUES_TOTAL`, and
`REOPENED_ISSUES` (§7's new-code-scoped conditions) can be computed as a plain count
with no ambiguity about which issues are in scope.

## Decision

An issue counts as new-code for gate purposes if and only if the matcher opened or
reopened it *in the run being evaluated* — `GateInput.GateIssue.newInThisRun()` /
`reopenedInThisRun()`, set by `IssueTrackingService` from the matching pass §3.3
already performed, and nothing else. `QualityGateEvaluator` is a pure function over
that flag; its own javadoc states the boundary explicitly: "this class never looks at
a diff, which is the whole point of ADR-008." `TOTAL_BLOCKER_ISSUES`, by contrast, is
deliberately *not* scoped to new code at all — `ConditionType`'s javadoc calls it
"overall," scoped to the whole project on the branch — because a gate that only ever
looked at new code could never catch a BLOCKER-severity issue that predates the gate
being turned on; §7's four conditions are a closed set specifically so a reviewer can
read all of them and know exactly what each one does and does not cover.

## Rejected alternatives

**Diff-based new-code detection** (an issue is new if its flagged line falls within
the PR's changed-lines range, computed from a unified diff against the base branch).
The most intuitive-sounding definition, and rejected for a reason more fundamental
than implementation cost: it is answering a different, weaker question than the one
that matters. A line-range diff cannot tell "this specific SQL-injection claim is new"
from "a line near an old, already-triaged SQL-injection claim happened to move because
of an unrelated edit two lines above it" — which is exactly §3's motivating problem,
one layer up. Scoping the gate to the diff would mean the gate's correctness rests on
the same naïve line-matching §3.1 already showed fails routinely, reintroducing false
positives and false negatives through the back door of the one component (the gate)
whose whole job is to be trustworthy enough that a team lets it block a merge. The
matcher already solved "is this the same claim as before" correctly (ADR-001); asking
the gate to re-derive a cruder version of the same answer from a diff would be
strictly worse *and* redundant.

**Date-based leak period** (a project sets a cutoff date or version tag; anything
first seen after it counts as new, à la SonarQube's classic "leak period"). A
reasonable, widely-used definition Sonar itself has offered — considered seriously,
not strawmanned. Rejected for v1 specifically because it answers "is this issue
recent" rather than "did *this* run introduce it," which is a coarser question: a
leak-period gate cannot distinguish a PR that introduces a new BLOCKER issue from a
PR that merely happens to land after the cutoff and touches nothing related, and it
needs a second, separate piece of state per project (the cutoff itself, and a policy
for when/how it moves) that the matcher-output definition needs none of — every run
already carries its own answer to "what did this run's matching open or reopen"
without any additional configuration. A leak-period mode is not ruled out in
principle; it is simply a different, coarser product decision than "make the gate as
precise as the matcher underneath it," and v1 chooses precision.

## Consequences

- The gate's trustworthiness is now provably bounded by the matcher's own accuracy —
  stated in §7 as the system's "central engineering argument," and the reason
  `matcher-corpus/`'s 0% false-merge / 0% false-split numbers (ADR-001) matter to more
  than just the matching feature in isolation: they are also, transitively, the gate's
  numbers.
- `QualityGateEvaluator` needs no git access, no diff computation, and no knowledge of
  branches or commits at all — it is a pure function of `GateInput`, which makes it
  trivially unit-testable (`QualityGateEvaluatorTest`, part of the dependency-free
  core verified by `scripts/offline-verify.sh`) with no database or SCM API involved.
- A PR that only reformats code and touches no logic will not fail the gate on
  reformatted lines it didn't actually introduce a new issue on — which is a direct,
  practical consequence of rungs 1 and 2 of the fingerprint ladder tolerating exactly
  that kind of edit (ADR-001).

## What would change our mind

A customer request for SonarQube-style leak-period semantics specifically (e.g.
migrating an existing SonarQube setup and wanting matching gate behaviour during
transition) would be a legitimate, additive reason to offer a second gate mode
alongside this one — not a reason to replace matcher-defined scoping, which stays the
more precise default either way.
