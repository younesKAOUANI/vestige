package dev.youneskaouani.vestige.gate.domain;

/**
 * The conditions a quality gate can be built from.
 *
 * <p>The set is deliberately small and closed rather than an expression language. A gate that a
 * reviewer cannot read at a glance is a gate that gets disabled the first time it is inconvenient,
 * and a closed set is what lets every condition report a number and a threshold in its result.
 */
public enum ConditionType {

    /** Fails when a new issue at or above the configured severity was introduced. */
    NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY,

    /** Fails when the run introduced more than the configured number of new issues. */
    MAX_NEW_ISSUES,

    /** Fails when a previously resolved issue came back. */
    NO_REOPENED_ISSUES
}
