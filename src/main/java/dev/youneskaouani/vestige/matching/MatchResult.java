package dev.youneskaouani.vestige.matching;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The outcome of matching one report against the previous state.
 *
 * <p>The three lists are a partition, and the compact constructor enforces it. That check is cheap
 * and it turns the two failure modes that would otherwise be invisible — losing an issue, or
 * reporting the same finding twice — into a loud failure at the point where the bug is.
 *
 * @param matches previously tracked issues that were sighted again
 * @param newFindings findings that correspond to no known issue and become new OPEN issues
 * @param disappearedIssues issues that were not sighted and become RESOLVED
 */
public record MatchResult(
        List<IssueMatch> matches,
        List<CandidateFinding> newFindings,
        List<TrackedIssue> disappearedIssues) {

    public MatchResult {
        matches = List.copyOf(matches);
        newFindings = List.copyOf(newFindings);
        disappearedIssues = List.copyOf(disappearedIssues);

        long distinctIssues = matches.stream().map(m -> m.issue().id()).distinct().count();
        if (distinctIssues != matches.size()) {
            throw new IllegalStateException("An issue was matched more than once");
        }
        long distinctFindings = matches.stream().map(m -> m.finding().id()).distinct().count();
        if (distinctFindings != matches.size()) {
            throw new IllegalStateException("A finding was matched more than once");
        }
    }

    /** Number of findings in the report this result was computed from. */
    public int findingCount() {
        return matches.size() + newFindings.size();
    }

    /** Number of previously tracked issues this result was computed from. */
    public int previousIssueCount() {
        return matches.size() + disappearedIssues.size();
    }

    /** Matches indexed by the id of the issue they re-sighted. */
    public Map<String, IssueMatch> byIssueId() {
        return matches.stream().collect(Collectors.toMap(m -> m.issue().id(), Function.identity()));
    }

    /** How many matches each strategy produced; useful for observability and for the demo script. */
    public Map<MatchStrategy, Long> strategyHistogram() {
        return matches.stream()
                .collect(Collectors.groupingBy(IssueMatch::strategy, Collectors.counting()));
    }
}
