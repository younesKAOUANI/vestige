package dev.youneskaouani.vestige.matching;

/**
 * Pass 4 — the offending line itself is unchanged, wherever it now lives.
 *
 * <p>The pre-image is only the rule id and the normalised text of the line, which makes this the
 * one pass that survives a file being renamed or a block being cut and pasted into a different
 * file. That freedom is also why its confidence is only 0.6: a line like {@code return null;} is
 * not distinctive, so the same key can legitimately describe several places in the codebase. Both
 * of the passes above it have already had their chance, so what reaches here is the residue, and
 * the ambiguity rule in {@link KeyedMatchPass} keeps the outcome reproducible.
 */
final class LineContentHashPass extends KeyedMatchPass {

    @Override
    public MatchStrategy strategy() {
        return MatchStrategy.LINE_CONTENT_HASH;
    }

    @Override
    protected String issueKey(TrackedIssue issue, DiffModel diff) {
        return issue.fingerprints().lineContent();
    }

    @Override
    protected String findingKey(CandidateFinding finding, DiffModel diff) {
        return finding.fingerprints().lineContent();
    }
}
