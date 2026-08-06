package dev.youneskaouani.vestige.gate.domain;

/**
 * One declarative condition of a quality gate: which check, and the count it tolerates.
 *
 * <p>The severity each {@link ConditionType} looks at is fixed by the type itself
 * ({@link ConditionType#NEW_CRITICAL_ISSUES} always means CRITICAL-or-above,
 * {@link ConditionType#TOTAL_BLOCKER_ISSUES} always means BLOCKER-or-above) rather than being a
 * free parameter. That is what keeps the gate a small closed set instead of an expression
 * language — see {@link ConditionType}.
 *
 * @param type which check to run
 * @param threshold the maximum tolerated count; the condition fails when the actual count exceeds
 *     it
 */
public record GateCondition(ConditionType type, long threshold) {

    public GateCondition {
        if (type == null) {
            throw new IllegalArgumentException("A gate condition needs a type");
        }
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative");
        }
    }

    /** A human-readable rendering, used in gate results and in the GitHub check run summary. */
    public String describe() {
        return switch (type) {
            case NEW_CRITICAL_ISSUES -> "new issues of severity >= CRITICAL <= %d".formatted(threshold);
            case NEW_ISSUES_TOTAL -> "new issue count <= %d".formatted(threshold);
            case REOPENED_ISSUES -> "reopened issue count <= %d".formatted(threshold);
            case TOTAL_BLOCKER_ISSUES -> "total BLOCKER issue count <= %d".formatted(threshold);
        };
    }
}
