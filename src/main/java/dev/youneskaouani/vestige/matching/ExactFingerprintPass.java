package dev.youneskaouani.vestige.matching;

/**
 * Pass 1 — the analyser told us the answer.
 *
 * <p>SARIF's {@code partialFingerprints} exists precisely so a tool that understands the code can
 * publish a stable identity for a result. When an analyser supplies one it beats anything Vestige
 * can infer from text, so this pass runs first and its matches are recorded at confidence 1.0.
 *
 * <p>The rule id is folded into the key: a fingerprint is only promised to be stable for a given
 * rule, and re-using it across rules would merge unrelated findings.
 */
final class ExactFingerprintPass extends KeyedMatchPass {

    @Override
    public MatchStrategy strategy() {
        return MatchStrategy.EXACT_FINGERPRINT;
    }

    @Override
    protected String issueKey(TrackedIssue issue, DiffModel diff) {
        return fingerprintKey(issue.ruleId(), issue.fingerprints().exact());
    }

    @Override
    protected String findingKey(CandidateFinding finding, DiffModel diff) {
        return fingerprintKey(finding.ruleId(), finding.fingerprints().exact());
    }

    private static String fingerprintKey(String ruleId, String exact) {
        return exact == null ? null : key(ruleId, exact);
    }
}
