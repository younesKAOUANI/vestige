package dev.youneskaouani.vestige.matching;

/** A previously tracked issue paired with the finding that re-sighted it. */
public record IssueMatch(TrackedIssue issue, CandidateFinding finding, MatchStrategy strategy) {

    public IssueMatch {
        if (issue == null || finding == null || strategy == null) {
            throw new IllegalArgumentException("IssueMatch requires an issue, a finding and a strategy");
        }
    }

    /** The confidence recorded on the resulting occurrence. */
    public double confidence() {
        return strategy.confidence();
    }
}
