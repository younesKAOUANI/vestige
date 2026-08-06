package dev.youneskaouani.vestige.gate.domain;

import java.util.List;

/**
 * The result of one condition, with enough detail to act on.
 *
 * @param condition the condition that was evaluated
 * @param status whether it held
 * @param actualValue the number the condition measured
 * @param threshold the number it was measured against
 * @param offendingIssueIds the issues responsible, capped so a catastrophic run does not produce a
 *     megabyte of JSON
 */
public record ConditionOutcome(
        GateCondition condition,
        GateStatus status,
        long actualValue,
        long threshold,
        List<String> offendingIssueIds) {

    /** How many offending issues a failing condition lists before it stops naming names. */
    public static final int MAX_REPORTED_ISSUES = 25;

    public ConditionOutcome {
        offendingIssueIds = List.copyOf(offendingIssueIds);
    }

    /** A one-line explanation, used in API responses and in the GitHub check run summary. */
    public String explain() {
        return "%s: %s (actual %d, threshold %d)"
                .formatted(condition.describe(), status, actualValue, threshold);
    }
}
