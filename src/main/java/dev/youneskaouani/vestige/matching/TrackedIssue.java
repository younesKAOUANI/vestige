package dev.youneskaouani.vestige.matching;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import java.util.Comparator;

/**
 * An issue as it was last seen, projected into the shape the matcher needs.
 *
 * <p>The location and the fingerprints are the ones recorded at the issue's <em>most recent</em>
 * occurrence, not at its first: matching always compares the head commit against the previous
 * sighting, so an issue that has drifted a hundred lines over fifty commits is still one hop away
 * from its next occurrence.
 */
public record TrackedIssue(
        String id,
        String ruleId,
        Severity severity,
        IssueStatus status,
        SourceLocation location,
        Fingerprints fingerprints) {

    /** A total order over issues, for the same reason as {@link CandidateFinding#CANONICAL_ORDER}. */
    public static final Comparator<TrackedIssue> CANONICAL_ORDER =
            Comparator.comparing((TrackedIssue i) -> i.location().path())
                    .thenComparingInt(i -> i.location().startLine())
                    .thenComparing(TrackedIssue::ruleId)
                    .thenComparing(TrackedIssue::id);

    public TrackedIssue {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TrackedIssue requires an id");
        }
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("TrackedIssue requires a ruleId");
        }
        if (location == null) {
            throw new IllegalArgumentException("TrackedIssue requires a location");
        }
        if (status == null) {
            status = IssueStatus.OPEN;
        }
        if (severity == null) {
            severity = Severity.MAJOR;
        }
        if (fingerprints == null) {
            fingerprints = Fingerprints.none();
        }
    }
}
