package dev.youneskaouani.vestige.matching;

/**
 * A veto used by the two passes that match on <em>position</em> rather than on content.
 *
 * <p>Pass 2 predicts where a finding should be and pass 5 accepts anything nearby. Both are
 * therefore capable of pairing two genuinely different violations of the same rule that happen to
 * end up at the same place — the classic case being a block of code moving while another block
 * takes over its line numbers.
 *
 * <p>When the report carries file contents we have independent evidence about identity, and it can
 * be used to refuse such a pair. The test is deliberately conservative: a contradiction is only
 * declared when <em>both</em> the enclosing block and the offending line are known on both sides
 * <em>and</em> both differ. One of the two changing is ordinary editing — someone reformatted the
 * method, or rewrote the statement the rule fired on — and must not cost the issue its history.
 * Both changing at once means nothing at all connects the two except a line number.
 *
 * <p>Reports without embedded sources have no fingerprints, so nothing is vetoed and the positional
 * passes behave exactly as they would without this class.
 */
final class ContentEvidence {

    private ContentEvidence() {
    }

    /** True when content evidence rules out this pairing. */
    static boolean contradicts(TrackedIssue issue, CandidateFinding finding) {
        return differs(issue.fingerprints().structural(), finding.fingerprints().structural())
                && differs(issue.fingerprints().lineContent(), finding.fingerprints().lineContent());
    }

    private static boolean differs(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }
}
