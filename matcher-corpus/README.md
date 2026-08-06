# matcher-corpus

The project's most valuable artefact (see the main [README](../README.md#the-matcher-and-why-it-is-the-centerpiece)
and [`docs/ARCHITECTURE.md` §3.4](../docs/ARCHITECTURE.md#34-how-this-gets-validated)):
32 hand-authored before/after fixtures, each one a real refactor shape, with the
*expected* matching recorded as ground truth. `MatcherCorpusHarnessTest` runs every
case through the real `IssueMatcher` — no mock, no reimplemented copy of the
algorithm — and fails the build if the false-split or false-merge rate moves.

A heuristic without a measurement is a guess. This directory is the measurement.

## Layout

```
matcher-corpus/
  cases/*.json          one fixture per file, NN-slug.json
  generate_cases.py      the script that produced them (see below)
```

`MatcherCorpusHarness.loadCases` parses every `*.json` file under `cases/` and
validates each one's internal references before scoring begins (`CorpusCase.validate()`).

## Fixture format

```json
{
  "id": "01-extract-method-preserves-flagged-line",
  "description": "Human-readable: what changed, and which fingerprint rung this exercises.",
  "refactorShape": "extract-method",
  "renames": {},
  "before": [
    {
      "id": "b1",
      "ruleId": "java:S3649",
      "filePath": "src/main/java/com/acme/PaymentService.java",
      "symbolPath": "com.acme.PaymentService#refund",
      "line": 6,
      "snippet": "        String sql = \"SELECT * FROM refunds WHERE id = \" + order.getId();"
    }
  ],
  "after": [
    {
      "id": "a1",
      "ruleId": "java:S3649",
      "filePath": "src/main/java/com/acme/PaymentService.java",
      "symbolPath": "com.acme.PaymentService#buildRefundQuery",
      "line": 11,
      "snippet": "        String sql = \"SELECT * FROM refunds WHERE id = \" + order.getId();"
    }
  ],
  "expectedMatches": [
    { "before": "b1", "after": "a1" }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Matches the filename (minus `.json`); how a failure identifies itself in a build log |
| `description` | What changed and, ideally, which rung of the fingerprint ladder the case is meant to exercise |
| `refactorShape` | A short tag (`extract-method`, `rename-symbol`, `reformat`, `move-file`, `inline-variable`, `wrap-in-try-catch`, …) — free text, not a closed enum, but kept consistent across cases for skimmability |
| `renames` | `{ "old/path.java": "new/path.java" }` — applied to the `before` side's file paths before fingerprinting, exactly as `IssueMatchingService` applies a real SCM rename map (§3.2) before comparing. Empty for every case that does not involve a file move |
| `before` / `after` | The finding sets on each side. `id` is a short per-case label (`b1`, `a2`, …), unique within its own side (`CorpusCase.validate()` rejects a duplicate); everything else (`ruleId`, `filePath`, `symbolPath`, `line`, `snippet`) is exactly the field `FingerprintFactory.compute` needs. `symbolPath` is `null`/omitted to exercise the no-`logicalLocations` degradation path (ADR-010) |
| `expectedMatches` | The ground truth: which `before.id` should match which `after.id`. A `before` finding with no entry here is expected to resolve (`RESOLVED_FIXED`); an `after` finding with no entry here is expected to open as a new issue |

## How a case is scored

`MatcherCorpusHarness.evaluate` feeds every case's `before`/`after` sides through one
real `IssueMatcher.match(...)` call and compares the pairings it produces against
`expectedMatches`, using the vocabulary `docs/ARCHITECTURE.md` §3.4 sets:

- **False split** — a pairing `expectedMatches` asserts that the matcher did not
  produce. The same real-world issue would be tracked as two unrelated ones.
- **False merge** — a pairing the matcher produced that is not in `expectedMatches`
  — either a previous issue matched to the wrong current finding, or two genuinely
  different issues collapsed into one.

The two rates are computed against **different denominators**, deliberately:

```
falseSplitRate = falseSplits / totalExpectedMatches   (of everything that should have matched, how much didn't)
falseMergeRate = falseMerges / totalActualMatches      (of everything the matcher claimed, how much was wrong)
```

A case that expects zero matches for a given finding (a genuinely new issue sitting
near old ones) contributes nothing to the split rate's denominator but still
penalises a wrong merge if the matcher pairs that finding anyway — which is why
several cases are deliberately adversarial rather than a clean 1:1 mapping:
`16-add-overload-does-not-merge-with-sibling` has a third, genuinely new overload
alongside two pre-existing ones the matcher must not attach it to;
`29-unrelated-new-issue-alongside-untouched-ones` mixes one real match with one
finding that must open fresh; `23-same-line-shift-different-rules-stay-separate`
matches every finding but is adversarial about *which* pairing — two different rules
land on adjacent, identically-shifted lines specifically to check the matcher does
not cross-attach them to each other's neighbour.

CI's bar (§3.4): **false-merge = 0%** (must never happen), **false-split ≤ 5%**. The
current run: **32 cases, 0% false-split, 0% false-merge** — reproducible with
`scripts/offline-verify.sh` or `mvn test`.

## Adding a case

1. Pick a real refactor shape you don't already have coverage for, or a shape you
   suspect might break a rung (a genuinely adversarial case is worth more than a
   redundant easy one).
2. Add it to `generate_cases.py` as a new scenario: real-looking `before`/`after`
   source strings, with `line_of`/`snippet_of` computing the line number and snippet
   text from those strings directly — a fixture can never claim a line or a snippet
   the source does not actually contain, because both are derived, not typed by hand.
3. Decide the ground truth yourself, as a human reading the two versions, and encode
   it as `expectedMatches` (or deliberately leave a finding unmatched).
4. Run `python3 matcher-corpus/generate_cases.py` to (re)write `cases/*.json`, then
   `scripts/offline-verify.sh` (or `mvn test`) to see whether the matcher agrees with
   you. If it doesn't, that is either a matcher bug or a rung genuinely operating at
   its designed limit (ADR-001's "What would change our mind" is the relevant
   question at that point) — not something to quietly relax the fixture to match.
