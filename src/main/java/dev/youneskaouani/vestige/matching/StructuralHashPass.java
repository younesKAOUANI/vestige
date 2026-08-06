package dev.youneskaouani.vestige.matching;

/**
 * Pass 3 — the enclosing block still hashes to the same value.
 *
 * <p>Pass 2 fails whenever the diff is absent, incomplete, or describes an edit big enough that the
 * line-level prediction misses. The structural hash contains no line numbers at all, so an issue
 * whose whole method simply moved down the file keeps the same key. The path is part of the
 * pre-image, so this pass does not survive a rename — pass 4 is there for that.
 */
final class StructuralHashPass extends KeyedMatchPass {

    @Override
    public MatchStrategy strategy() {
        return MatchStrategy.STRUCTURAL_HASH;
    }

    @Override
    protected String issueKey(TrackedIssue issue, DiffModel diff) {
        return issue.fingerprints().structural();
    }

    @Override
    protected String findingKey(CandidateFinding finding, DiffModel diff) {
        return finding.fingerprints().structural();
    }
}
