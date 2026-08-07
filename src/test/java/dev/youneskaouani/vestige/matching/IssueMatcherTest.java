package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for §3.3's algorithm in isolation. {@code matcher-corpus/} (see
 * MatcherCorpusHarnessTest) exercises the same class end-to-end against ~30 hand-authored
 * before/after code pairs; these tests instead pin down the algorithm's own rules - rung order,
 * tie-breaking, determinism - with fingerprints built directly so each case is unambiguous about
 * which rung is meant to fire.
 */
class IssueMatcherTest {

    private static final int WEAK_PROXIMITY = 25;

    private final IssueMatcher matcher = new IssueMatcher(WEAK_PROXIMITY);

    private static PreviousIssueCandidate previous(long seq, int line, Fingerprints fingerprints) {
        return new PreviousIssueCandidate(UUID.randomUUID(), seq, line, fingerprints);
    }

    private static IncomingFinding current(int ordinal, int line, Fingerprints fingerprints) {
        return new IncomingFinding(ordinal, line, fingerprints);
    }

    private static Fingerprints fp(String ruleId, String path, String symbolPath, String snippet) {
        return FingerprintFactory.compute(ruleId, path, symbolPath, snippet);
    }

    // ---------------------------------------------------------------------------------------
    // Rung 1: identity_fp
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "matches on identity_fp when the symbol path is stable, regardless of line and literal changes")
    void matchesOnIdentityFingerprint() {
        Fingerprints beforeFp =
                fp("java:S3649", "PaymentService.java", "PaymentService#refund", "sql = a + 1");
        Fingerprints afterFp =
                fp("java:S3649", "PaymentService.java", "PaymentService#refund", "sql = b + 2");

        PreviousIssueCandidate p = previous(1, 42, beforeFp);
        IncomingFinding c = current(0, 58, afterFp);

        MatchResult result = matcher.match(List.of(p), List.of(c));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rung()).isEqualTo(Rung.IDENTITY);
        assertThat(result.matches().get(0).previous()).isEqualTo(p);
        assertThat(result.newIssues()).isEmpty();
        assertThat(result.noLongerPresent()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Rung 2: context_fp (identity_fp unavailable - no symbol path)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("falls back to context_fp when the analyser supplies no symbol path")
    void fallsBackToContextFingerprintWithoutASymbolPath() {
        Fingerprints beforeFp =
                fp("java:S2259", "OrderService.java", null, "  return order.normalise();");
        Fingerprints afterFp =
                fp("java:S2259", "OrderService.java", null, "return order.normalise();  ");

        PreviousIssueCandidate p = previous(1, 12, beforeFp);
        IncomingFinding c = current(0, 20, afterFp);

        MatchResult result = matcher.match(List.of(p), List.of(c));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rung()).isEqualTo(Rung.CONTEXT);
    }

    @Test
    @DisplayName(
            "identity_fp is tried before context_fp: strong evidence wins even when a weaker match exists too")
    void identityBeatsContextWhenBothWouldMatch() {
        String snippet = "return order.normalise();";
        Fingerprints withSymbol =
                fp("java:S2259", "OrderService.java", "OrderService#find", snippet);

        PreviousIssueCandidate viaIdentityAndContext = previous(1, 12, withSymbol);
        IncomingFinding c = current(0, 12, withSymbol);

        MatchResult result = matcher.match(List.of(viaIdentityAndContext), List.of(c));

        assertThat(result.matches().get(0).rung()).isEqualTo(Rung.IDENTITY);
    }

    // ---------------------------------------------------------------------------------------
    // Rung 3: weak_fp + line proximity
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "falls back to weak_fp within the line-proximity window when neither stronger rung applies")
    void fallsBackToWeakFingerprintWithinProximity() {
        Fingerprints beforeFp = fp("java:S3649", "PaymentService.java", null, null);
        Fingerprints afterFp = fp("java:S3649", "PaymentService.java", null, null);

        PreviousIssueCandidate p = previous(1, 42, beforeFp);
        IncomingFinding c = current(0, 42 + WEAK_PROXIMITY, afterFp);

        MatchResult result = matcher.match(List.of(p), List.of(c));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rung()).isEqualTo(Rung.WEAK);
    }

    @Test
    @DisplayName(
            "weak_fp respects the proximity boundary exactly: one line beyond it never matches")
    void weakFingerprintRespectsTheProximityBoundary() {
        Fingerprints beforeFp = fp("java:S3649", "PaymentService.java", null, null);
        Fingerprints afterFp = fp("java:S3649", "PaymentService.java", null, null);

        PreviousIssueCandidate p = previous(1, 42, beforeFp);
        IncomingFinding tooFar = current(0, 42 + WEAK_PROXIMITY + 1, afterFp);

        MatchResult result = matcher.match(List.of(p), List.of(tooFar));

        assertThat(result.matches()).isEmpty();
        assertThat(result.newIssues()).containsExactly(tooFar);
        assertThat(result.noLongerPresent()).containsExactly(p);
    }

    @Test
    @DisplayName(
            "§3.1's own worked example: a renamed method AND a renamed parameter defeats rungs 1 and 2, "
                    + "leaving rung 3 as the one that actually resolves it")
    void resolvesTheArchitectureDocsWorkedExampleViaWeakRungOnly() {
        // Run 1, commit a1b2c3, PaymentService.java line 42:
        //   public void refund(Order o) {
        //       String sql = "SELECT * FROM refunds WHERE id = " + o.getId();
        Fingerprints run1 =
                fp(
                        "java:S3649",
                        "PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = \"SELECT * FROM refunds WHERE id = \" + o.getId();");

        // Run 2, commit d4e5f6, line 58 (12 lines of added imports shifted it, well within the
        // +-25 window): the method AND its parameter were renamed.
        //   public void issueRefund(Order order) {
        //       String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        Fingerprints run2 =
                fp(
                        "java:S3649",
                        "PaymentService.java",
                        "com.acme.PaymentService#issueRefund",
                        "String sql = \"SELECT * FROM refunds WHERE id = \" + order.getId();");

        // Both rungs that could theoretically survive a lesser edit are defeated by this one:
        assertThat(run1.identityFp())
                .as(
                        "the enclosing method's own name changed, so identity_fp cannot survive this edit")
                .isNotEqualTo(run2.identityFp());
        assertThat(run1.contextFp())
                .as(
                        "the flagged line's own text changed (o -> order), which is exactly what context_fp "
                                + "does not tolerate (§3.2's own \"breaks on\" column)")
                .isNotEqualTo(run2.contextFp());
        assertThat(run1.weakFp())
                .as(
                        "rule id and file path are untouched, so weak_fp is unaffected by either rename")
                .isEqualTo(run2.weakFp());

        PreviousIssueCandidate p = previous(1, 42, run1);
        IncomingFinding c = current(0, 58, run2);

        MatchResult result = matcher.match(List.of(p), List.of(c));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rung())
                .as("this is exactly why the ladder has three rungs and not one")
                .isEqualTo(Rung.WEAK);
        assertThat(result.newIssues()).isEmpty();
        assertThat(result.noLongerPresent()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Determinism and tie-breaking
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("ties within a bucket resolve by line proximity, then by lowest finding id")
    void tieBreaksByProximityThenLowestId() {
        Fingerprints sharedFp = fp("java:S3649", "File.java", null, null);

        PreviousIssueCandidate near = previous(5, 100, sharedFp);
        PreviousIssueCandidate exact = previous(2, 103, sharedFp);
        PreviousIssueCandidate alsoExact = previous(1, 103, sharedFp); // ties `exact` on distance

        IncomingFinding c = current(0, 103, sharedFp);

        MatchResult result = matcher.match(List.of(near, exact, alsoExact), List.of(c));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).previous())
                .as(
                        "distance 0 beats distance 3, and between the two distance-0 candidates the lowest "
                                + "finding id (seq=1) wins")
                .isEqualTo(alsoExact);
    }

    @Test
    @DisplayName(
            "two current findings competing for the same bucket each get the closest available candidate")
    void twoCurrentFindingsSplitACommonBucket() {
        Fingerprints sharedFp = fp("java:S3649", "File.java", null, null);

        PreviousIssueCandidate atTen = previous(1, 10, sharedFp);
        PreviousIssueCandidate atTwenty = previous(2, 20, sharedFp);

        IncomingFinding closeToTen = current(0, 11, sharedFp);
        IncomingFinding closeToTwenty = current(1, 21, sharedFp);

        MatchResult result =
                matcher.match(List.of(atTen, atTwenty), List.of(closeToTen, closeToTwenty));

        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches())
                .anySatisfy(
                        m -> {
                            assertThat(m.previous()).isEqualTo(atTen);
                            assertThat(m.current()).isEqualTo(closeToTen);
                        })
                .anySatisfy(
                        m -> {
                            assertThat(m.previous()).isEqualTo(atTwenty);
                            assertThat(m.current()).isEqualTo(closeToTwenty);
                        });
    }

    @Test
    @DisplayName(
            "running the same inputs twice produces an identical matching - required for replay (§4.2)")
    void isDeterministicAcrossRepeatedRuns() {
        Fingerprints fpA = fp("java:S3649", "A.java", "A#m", "line a");
        Fingerprints fpB = fp("java:S2259", "B.java", null, "line b");
        Fingerprints fpC = fp("java:S1181", "C.java", null, null);

        List<PreviousIssueCandidate> previous =
                List.of(previous(1, 10, fpA), previous(2, 20, fpB), previous(3, 30, fpC));
        List<IncomingFinding> current =
                List.of(current(0, 11, fpA), current(1, 21, fpB), current(2, 31, fpC));

        MatchResult first = matcher.match(previous, current);
        MatchResult second = matcher.match(previous, current);

        assertThat(first).isEqualTo(second);
    }

    // ---------------------------------------------------------------------------------------
    // New issues, auto-resolution, and edge cases
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a finding matching no rung opens a new issue")
    void unmatchedCurrentFindingOpensANewIssue() {
        MatchResult result =
                matcher.match(List.of(), List.of(current(0, 1, fp("r", "f", null, null))));

        assertThat(result.newIssues()).hasSize(1);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    @DisplayName("a previous issue matching no current finding is reported as no-longer-present")
    void unmatchedPreviousIssueIsAutoResolved() {
        PreviousIssueCandidate p = previous(1, 1, fp("r", "f", null, null));

        MatchResult result = matcher.match(List.of(p), List.of());

        assertThat(result.noLongerPresent()).containsExactly(p);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    @DisplayName("an empty run against an empty baseline matches nothing and opens nothing")
    void handlesEmptyInputs() {
        MatchResult result = matcher.match(List.of(), List.of());

        assertThat(result.matches()).isEmpty();
        assertThat(result.newIssues()).isEmpty();
        assertThat(result.noLongerPresent()).isEmpty();
    }

    @Test
    @DisplayName(
            "rejects two previous candidates for the same issue id, which would make removal ambiguous")
    void rejectsDuplicateIssueIds() {
        UUID issueId = UUID.randomUUID();
        Fingerprints fingerprints = fp("r", "f", null, null);
        PreviousIssueCandidate first = new PreviousIssueCandidate(issueId, 1, 1, fingerprints);
        PreviousIssueCandidate duplicate = new PreviousIssueCandidate(issueId, 2, 2, fingerprints);

        assertThatThrownBy(() -> matcher.match(List.of(first, duplicate), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a negative line-proximity window at construction")
    void rejectsANegativeProximityWindow() {
        assertThatThrownBy(() -> new IssueMatcher(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
