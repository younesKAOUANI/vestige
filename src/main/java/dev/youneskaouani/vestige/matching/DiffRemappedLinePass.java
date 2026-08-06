package dev.youneskaouani.vestige.matching;

import java.util.OptionalInt;

/**
 * Pass 2 — translate the previous position into head-commit coordinates and look for the finding
 * there.
 *
 * <p>This is the pass that does the real work on a normal pull request. Nearly every finding that
 * survives an edit survives it <em>unchanged</em>, just displaced: someone added an import, or
 * deleted a method above. Walking the diff turns that displacement into an exact prediction of
 * where the finding must now be, and an exact prediction that comes true is strong evidence.
 *
 * <p>When no diff was supplied the translation is the identity, and the pass degenerates into
 * "same rule, same file, same line" — still the correct answer for an unchanged file. The matcher
 * therefore never <em>depends</em> on the diff, it only gets better with one.
 *
 * <p>A line that the diff deleted has no head-commit coordinate, so no key is produced and the
 * issue falls through to the content-based passes; if the code really was deleted they will not
 * match it either and the issue is resolved, which is the right outcome.
 */
final class DiffRemappedLinePass extends KeyedMatchPass {

    @Override
    public MatchStrategy strategy() {
        return MatchStrategy.DIFF_REMAPPED_LINE;
    }

    @Override
    protected String issueKey(TrackedIssue issue, DiffModel diff) {
        String previousPath = issue.location().path();
        OptionalInt remapped = diff.translateLine(previousPath, issue.location().startLine());
        if (remapped.isEmpty()) {
            return null;
        }
        return key(issue.ruleId(), diff.mapPath(previousPath), Integer.toString(remapped.getAsInt()));
    }

    @Override
    protected String findingKey(CandidateFinding finding, DiffModel diff) {
        return key(
                finding.ruleId(),
                finding.location().path(),
                Integer.toString(finding.location().startLine()));
    }

    /**
     * A predicted position is evidence, not proof. When the report also carries file contents and
     * they say the two are unrelated, believe the contents: see {@link ContentEvidence}.
     */
    @Override
    protected boolean admissible(TrackedIssue issue, CandidateFinding finding) {
        return !ContentEvidence.contradicts(issue, finding);
    }
}
