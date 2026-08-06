package dev.youneskaouani.vestige.common.domain;

/**
 * Lifecycle state of a tracked issue.
 *
 * <p>{@link #OPEN} and {@link #REOPENED} are set by the ingestion pipeline; {@link #ACCEPTED} and
 * {@link #FALSE_POSITIVE} are only ever set by a human through triage; {@link #RESOLVED} is set by
 * the pipeline when an issue stops being reported, and is the state a later sighting reopens from.
 */
public enum IssueStatus {
    OPEN,
    RESOLVED,
    REOPENED,
    ACCEPTED,
    FALSE_POSITIVE;

    /** True when the issue is currently counted as an outstanding defect. */
    public boolean isOutstanding() {
        return this == OPEN || this == REOPENED;
    }

    /**
     * True when a human has deliberately silenced the issue.
     *
     * <p>Silenced issues are still matched across commits — otherwise every run would resurrect
     * them as brand-new findings and the triage decision would have to be repeated forever — but
     * they do not fail quality gates.
     */
    public boolean isSilenced() {
        return this == ACCEPTED || this == FALSE_POSITIVE;
    }
}
