package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable state shared by the passes: what is still unclaimed, and what has been paired so far.
 *
 * <p>Both collections are seeded in canonical order and only ever shrink, so every pass sees a
 * deterministic sequence regardless of the order the caller supplied. Claiming is the single point
 * where an issue and a finding leave the pool, which is what enforces "each pass only considers
 * issues not already claimed by an earlier pass".
 */
final class MatchingWorkspace {

    private final Map<String, TrackedIssue> unclaimedIssues = new LinkedHashMap<>();
    private final Map<String, CandidateFinding> unclaimedFindings = new LinkedHashMap<>();
    private final List<IssueMatch> matches = new ArrayList<>();
    private final DiffModel diff;

    MatchingWorkspace(MatchRequest request) {
        this.diff = request.diff();
        request.previousIssues().stream()
                .sorted(TrackedIssue.CANONICAL_ORDER)
                .forEach(issue -> unclaimedIssues.put(issue.id(), issue));
        request.findings().stream()
                .sorted(CandidateFinding.CANONICAL_ORDER)
                .forEach(finding -> unclaimedFindings.put(finding.id(), finding));
    }

    DiffModel diff() {
        return diff;
    }

    /** Unclaimed issues, in canonical order. */
    List<TrackedIssue> unclaimedIssues() {
        return List.copyOf(unclaimedIssues.values());
    }

    /** Unclaimed findings, in canonical order. */
    List<CandidateFinding> unclaimedFindings() {
        return List.copyOf(unclaimedFindings.values());
    }

    /** Pairs an issue with a finding and removes both from the pool. */
    void claim(TrackedIssue issue, CandidateFinding finding, MatchStrategy strategy) {
        TrackedIssue removedIssue = unclaimedIssues.remove(issue.id());
        CandidateFinding removedFinding = unclaimedFindings.remove(finding.id());
        if (removedIssue == null || removedFinding == null) {
            throw new IllegalStateException(
                    "Pass " + strategy + " tried to claim an already-claimed issue or finding");
        }
        matches.add(new IssueMatch(issue, finding, strategy));
    }

    List<IssueMatch> matches() {
        return Collections.unmodifiableList(matches);
    }
}
