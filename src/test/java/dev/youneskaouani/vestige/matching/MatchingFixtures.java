package dev.youneskaouani.vestige.matching;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import java.util.List;
import java.util.Map;

/**
 * Builders that mirror what the ingestion pipeline does, so the matcher's tests exercise the same
 * shapes production code produces.
 *
 * <p>In particular {@link #trackedFrom} derives an issue's fingerprints from the occurrence that
 * last saw it, which is exactly how {@code IssueTrackingService} persists them.
 */
final class MatchingFixtures {

    private MatchingFixtures() {
    }

    static SourceSnapshot snapshot(String path, List<String> lines) {
        return SourceSnapshot.ofFileContents(Map.of(path, String.join("\n", lines)));
    }

    static CandidateFinding finding(
            String id, String ruleId, String path, int line, SourceSnapshot snapshot) {
        return finding(id, ruleId, path, line, snapshot, null);
    }

    static CandidateFinding finding(
            String id,
            String ruleId,
            String path,
            int line,
            SourceSnapshot snapshot,
            String exactFingerprint) {
        SourceLocation location = SourceLocation.ofLine(path, line);
        Fingerprints fingerprints =
                new FingerprintCalculator(snapshot).compute(ruleId, location, exactFingerprint);
        return new CandidateFinding(id, ruleId, Severity.MAJOR, ruleId + " triggered", location, fingerprints);
    }

    static TrackedIssue trackedFrom(String issueId, CandidateFinding finding) {
        return trackedFrom(issueId, finding, IssueStatus.OPEN);
    }

    static TrackedIssue trackedFrom(String issueId, CandidateFinding finding, IssueStatus status) {
        return new TrackedIssue(
                issueId,
                finding.ruleId(),
                finding.severity(),
                status,
                finding.location(),
                finding.fingerprints());
    }
}
