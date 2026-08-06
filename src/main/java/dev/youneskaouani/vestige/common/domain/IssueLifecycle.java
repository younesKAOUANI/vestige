package dev.youneskaouani.vestige.common.domain;

/**
 * The status transitions the ingestion pipeline is allowed to make on its own.
 *
 * <p>Keeping them here, as a pure function of the current status, means the rule is stated once and
 * tested directly rather than being spread through the tracking service as a chain of ifs.
 *
 * <p>The rule that carries the most weight is that a human decision is never overwritten by a
 * machine: an {@link IssueStatus#ACCEPTED} or {@link IssueStatus#FALSE_POSITIVE} issue stays that
 * way whether or not the analyser sights it again. Only a person, through triage, can move it back.
 */
public final class IssueLifecycle {

    private IssueLifecycle() {
    }

    /** The status of an issue that this run sighted again. */
    public static IssueStatus afterSighting(IssueStatus current) {
        if (current.isSilenced()) {
            return current;
        }
        return current == IssueStatus.RESOLVED ? IssueStatus.REOPENED : current;
    }

    /** The status of an issue that this run did not sight. */
    public static IssueStatus afterDisappearance(IssueStatus current) {
        return current.isSilenced() ? current : IssueStatus.RESOLVED;
    }

    /** True when this run turned a resolved issue back into an open one. */
    public static boolean isReopening(IssueStatus before, IssueStatus after) {
        return before == IssueStatus.RESOLVED && after == IssueStatus.REOPENED;
    }
}
