package dev.youneskaouani.vestige.gate.domain;

/**
 * The four conditions a quality gate can be built from, exactly as named in ARCHITECTURE.md §7.
 *
 * <p>The set is deliberately small and closed rather than an expression language. A gate a reviewer
 * cannot read at a glance is a gate that gets disabled the first time it is inconvenient, and a
 * closed set is what lets every condition report a number and a threshold in its result.
 *
 * <p>{@link #NEW_CRITICAL_ISSUES}, {@link #NEW_ISSUES_TOTAL} and {@link #REOPENED_ISSUES} are
 * scoped to <b>new code</b> — issues the matcher opened or reopened in the run being evaluated. §7
 * is explicit that this scope is "defined by the matcher, not by the diff": there is deliberately
 * no separate "on a changed line" restriction here (ADR-008). {@link #TOTAL_BLOCKER_ISSUES} is
 * scoped to the whole project on this branch, which is what makes it catch debt that predates the
 * current run.
 */
public enum ConditionType {

    /** New code: fails when a new or reopened issue at severity CRITICAL or above was matched. */
    NEW_CRITICAL_ISSUES,

    /** New code: fails when more than the configured number of issues were newly opened. */
    NEW_ISSUES_TOTAL,

    /** New code: fails when a previously resolved issue came back. */
    REOPENED_ISSUES,

    /**
     * Overall: fails when the project carries more than the configured number of BLOCKER issues.
     */
    TOTAL_BLOCKER_ISSUES
}
