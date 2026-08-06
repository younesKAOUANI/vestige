package dev.youneskaouani.vestige.gate.domain;

import dev.youneskaouani.vestige.common.domain.Severity;

/**
 * One declarative condition of a quality gate.
 *
 * @param type which check to run
 * @param severityThreshold the severity at or above which issues count, for severity conditions
 * @param countThreshold the maximum tolerated count, for counting conditions
 * @param changedLinesOnly restrict the condition to issues sitting on lines this run's diff
 *     touched, which is what makes a gate adoptable on a codebase with existing debt
 */
public record GateCondition(
        ConditionType type, Severity severityThreshold, int countThreshold, boolean changedLinesOnly) {

    public GateCondition {
        if (type == null) {
            throw new IllegalArgumentException("A gate condition needs a type");
        }
        if (type == ConditionType.NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY && severityThreshold == null) {
            throw new IllegalArgumentException(type + " needs a severity threshold");
        }
        if (type == ConditionType.MAX_NEW_ISSUES && countThreshold < 0) {
            throw new IllegalArgumentException(type + " needs a non-negative count threshold");
        }
    }

    public static GateCondition noNewIssuesAtOrAbove(Severity severity, boolean changedLinesOnly) {
        return new GateCondition(
                ConditionType.NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY, severity, 0, changedLinesOnly);
    }

    public static GateCondition maxNewIssues(int max) {
        return new GateCondition(ConditionType.MAX_NEW_ISSUES, null, max, false);
    }

    public static GateCondition noReopenedIssues() {
        return new GateCondition(ConditionType.NO_REOPENED_ISSUES, null, 0, false);
    }

    /** A human-readable rendering, used in gate results and in the GitHub check run summary. */
    public String describe() {
        return switch (type) {
            case NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY -> "no new issues of severity >= %s%s"
                    .formatted(severityThreshold, changedLinesOnly ? " on changed lines" : "");
            case MAX_NEW_ISSUES -> "new issue count <= %d".formatted(countThreshold);
            case NO_REOPENED_ISSUES -> "no reopened issues";
        };
    }
}
