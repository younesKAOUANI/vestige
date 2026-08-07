package dev.youneskaouani.vestige.common.domain;

/**
 * The status transitions the matcher (§3.3) is allowed to make on its own, as a pure function of
 * the current status. Keeping the rule here, rather than spread through the tracking service as a
 * chain of ifs, means it is stated once and tested directly.
 *
 * <p>§3.3's pseudocode states the common case plainly: {@code for p in unmatched_P: p.status <-
 * RESOLVED_FIXED (auto)}. It does not spell out what happens to an issue a human has already
 * triaged away, because the domain narrative in §2.2 answers that separately: an issue goes "back
 * to REOPENED if it reappears <em>after being resolved</em>". Read literally, "resolved" already
 * excludes the two triage outcomes ({@link IssueStatus#RESOLVED_FALSE_POSITIVE}, {@link
 * IssueStatus#RESOLVED_WONT_FIX}) — those are human judgements about code that may still be flagged
 * every single run, and flipping them back to {@code REOPENED} the next time the matcher sees the
 * same fingerprint would make triage pointless: the gate would fail again on a decision the team
 * already took (see {@code QualityGateEvaluator}, which excludes silenced issues for the identical
 * reason). So the rule this class encodes is:
 *
 * <ul>
 *   <li>a human decision ({@link IssueStatus#isSilenced()}) is never overwritten by a re-sighting
 *       or a disappearance — only another human, through triage, moves it;
 *   <li>a re-sighting of a {@link IssueStatus#RESOLVED_FIXED} issue reopens it — the one case §2.2
 *       names explicitly;
 *   <li>a re-sighting of an already-{@link IssueStatus#OPEN} or {@link IssueStatus#REOPENED} issue
 *       changes nothing;
 *   <li>the disappearance of an outstanding (open or reopened) issue auto-resolves it, exactly as
 *       §3.3's pseudocode states.
 * </ul>
 */
public final class IssueLifecycle {

    private IssueLifecycle() {}

    /** The status of an issue whose fingerprint this run matched again. */
    public static IssueStatus afterSighting(IssueStatus current) {
        if (current.isSilenced()) {
            return current;
        }
        return current == IssueStatus.RESOLVED_FIXED ? IssueStatus.REOPENED : current;
    }

    /** The status of a previously-tracked issue this run did not match. */
    public static IssueStatus afterDisappearance(IssueStatus current) {
        return current.isOutstanding() ? IssueStatus.RESOLVED_FIXED : current;
    }

    /** True when this run turned a resolved issue back into an open one. */
    public static boolean isReopening(IssueStatus before, IssueStatus after) {
        return before == IssueStatus.RESOLVED_FIXED && after == IssueStatus.REOPENED;
    }
}
