package dev.youneskaouani.vestige.matching;

import java.util.List;

/**
 * The full outcome of one {@link IssueMatcher#match} call, covering every previous candidate and
 * every current finding exactly once.
 *
 * @param matches previous issues re-identified in this run, with the rung that did it
 * @param newIssues current findings that matched nothing - §3.3's {@code for c in unmatched_C: open
 *     new Issue}
 * @param noLongerPresent previous issues no current finding matched - §3.3's {@code for p in
 *     unmatched_P: p.status <- RESOLVED_FIXED (auto)}
 */
public record MatchResult(
        List<Match> matches,
        List<IncomingFinding> newIssues,
        List<PreviousIssueCandidate> noLongerPresent) {

    public MatchResult {
        matches = List.copyOf(matches);
        newIssues = List.copyOf(newIssues);
        noLongerPresent = List.copyOf(noLongerPresent);
    }
}
