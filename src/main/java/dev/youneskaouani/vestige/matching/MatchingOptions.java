package dev.youneskaouani.vestige.matching;

/**
 * Tuning knobs for the matcher.
 *
 * @param positionalDriftWindow how far a finding may move, in lines, and still be considered the
 *     same issue by the last-resort positional pass
 */
public record MatchingOptions(int positionalDriftWindow) {

    /**
     * Ten lines. Small enough that two independent violations of the same rule in one file are
     * unlikely to be confused, large enough to absorb an edit the diff did not describe.
     */
    public static final int DEFAULT_POSITIONAL_DRIFT_WINDOW = 10;

    public MatchingOptions {
        if (positionalDriftWindow < 0) {
            throw new IllegalArgumentException("positionalDriftWindow must not be negative");
        }
    }

    public static MatchingOptions defaults() {
        return new MatchingOptions(DEFAULT_POSITIONAL_DRIFT_WINDOW);
    }
}
