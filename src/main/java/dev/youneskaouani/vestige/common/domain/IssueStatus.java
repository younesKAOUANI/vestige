package dev.youneskaouani.vestige.common.domain;

/**
 * Lifecycle state of a tracked issue, exactly as named in ARCHITECTURE.md §2.2.
 *
 * <p>{@link #OPEN} and {@link #REOPENED} are set by the matcher (§3.3); {@link #RESOLVED_FIXED} is
 * also set by the matcher, automatically, when an issue's fingerprint stops appearing in a run.
 * {@link #RESOLVED_FALSE_POSITIVE} and {@link #RESOLVED_WONT_FIX} are only ever set by a human,
 * through triage (§6), and carry a mandatory justification.
 */
public enum IssueStatus {
    OPEN,
    RESOLVED_FIXED,
    RESOLVED_FALSE_POSITIVE,
    RESOLVED_WONT_FIX,
    REOPENED;

    /** True when the issue is currently counted as an outstanding defect. */
    public boolean isOutstanding() {
        return this == OPEN || this == REOPENED;
    }

    /**
     * True when a human has deliberately silenced the issue.
     *
     * <p>Silenced issues are still matched across commits — otherwise every run would resurrect
     * them as brand-new findings and the triage decision would have to be repeated forever — but
     * they do not fail quality gates (§7, {@code countsAgainstTheGate}).
     */
    public boolean isSilenced() {
        return this == RESOLVED_FALSE_POSITIVE || this == RESOLVED_WONT_FIX;
    }

    /** True when a human, rather than the matcher, is the only actor allowed to set this status. */
    public boolean requiresTriage() {
        return this == RESOLVED_FALSE_POSITIVE || this == RESOLVED_WONT_FIX;
    }
}
