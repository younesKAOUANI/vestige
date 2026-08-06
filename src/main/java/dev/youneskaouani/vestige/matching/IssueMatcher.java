package dev.youneskaouani.vestige.matching;

import java.util.List;

/**
 * Decides, for every finding in an incoming report, whether it is a new problem or the next
 * sighting of a problem the project already knows about.
 *
 * <p>Five passes run in decreasing order of confidence. A pass may only pair issues and findings
 * that no earlier pass claimed, so the strongest available evidence always wins and no issue or
 * finding is ever used twice. What is left over at the end is unambiguous: unclaimed findings are
 * genuinely new, unclaimed issues have genuinely disappeared.
 *
 * <ol>
 *   <li>{@link ExactFingerprintPass} &mdash; the analyser's own {@code partialFingerprints} (1.0)
 *   <li>{@link DiffRemappedLinePass} &mdash; the previous line, translated through the diff (0.9)
 *   <li>{@link StructuralHashPass} &mdash; hash of the enclosing block (0.8)
 *   <li>{@link LineContentHashPass} &mdash; hash of the offending line (0.6)
 *   <li>{@link PositionalFallbackPass} &mdash; same rule and file, bounded drift (0.3)
 * </ol>
 *
 * <p>The class is immutable and stateless between calls; a single instance is safe to share.
 */
public final class IssueMatcher {

    private final List<MatchPass> passes;

    public IssueMatcher() {
        this(MatchingOptions.defaults());
    }

    public IssueMatcher(MatchingOptions options) {
        this.passes = List.of(
                new ExactFingerprintPass(),
                new DiffRemappedLinePass(),
                new StructuralHashPass(),
                new LineContentHashPass(),
                new PositionalFallbackPass(options.positionalDriftWindow()));
    }

    /** Runs the pipeline. The result is a partition of the request's issues and findings. */
    public MatchResult match(MatchRequest request) {
        MatchingWorkspace workspace = new MatchingWorkspace(request);
        for (MatchPass pass : passes) {
            pass.run(workspace);
        }
        MatchResult result = new MatchResult(
                workspace.matches(), workspace.unclaimedFindings(), workspace.unclaimedIssues());
        verifyPartition(request, result);
        return result;
    }

    /**
     * Guards the property the whole feature rests on: nothing is lost and nothing is duplicated.
     *
     * <p>{@link MatchResult} already refuses to hold an issue or finding twice; this adds the other
     * half, that the counts still add up to what came in. Both are cheap, and a silent violation
     * here would show up much later as an issue history that inexplicably restarts.
     */
    private static void verifyPartition(MatchRequest request, MatchResult result) {
        if (result.findingCount() != request.findings().size()) {
            throw new IllegalStateException(
                    "Matcher lost or invented findings: in=%d out=%d"
                            .formatted(request.findings().size(), result.findingCount()));
        }
        if (result.previousIssueCount() != request.previousIssues().size()) {
            throw new IllegalStateException(
                    "Matcher lost or invented issues: in=%d out=%d"
                            .formatted(request.previousIssues().size(), result.previousIssueCount()));
        }
    }
}
