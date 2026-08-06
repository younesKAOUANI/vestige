package dev.youneskaouani.vestige.matching;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the matcher needs for one run: the issues carried over from the previous analysis, the
 * findings in the incoming report, and (optionally) the diff between the two commits.
 */
public record MatchRequest(
        List<TrackedIssue> previousIssues, List<CandidateFinding> findings, DiffModel diff) {

    public MatchRequest {
        previousIssues = List.copyOf(previousIssues);
        findings = List.copyOf(findings);
        diff = diff == null ? DiffModel.empty() : diff;
        requireUniqueIds(previousIssues.stream().map(TrackedIssue::id).toList(), "previousIssues");
        requireUniqueIds(findings.stream().map(CandidateFinding::id).toList(), "findings");
    }

    /** Convenience factory for the very first analysis of a project, where nothing is carried over. */
    public static MatchRequest firstRun(List<CandidateFinding> findings) {
        return new MatchRequest(List.of(), findings, DiffModel.empty());
    }

    private static void requireUniqueIds(List<String> ids, String what) {
        Set<String> seen = new HashSet<>(ids.size());
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate id in " + what + ": " + id);
            }
        }
    }
}
