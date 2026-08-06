package dev.youneskaouani.vestige.gate.domain;

import java.util.List;

/** A named set of conditions, all of which must hold. */
public record QualityGateDefinition(String name, List<GateCondition> conditions) {

    public QualityGateDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A quality gate needs a name");
        }
        conditions = List.copyOf(conditions);
    }

    /**
     * The gate a project gets until someone configures one — the exact thresholds tabulated in
     * ARCHITECTURE.md §7.
     */
    public static QualityGateDefinition defaultGate() {
        return new QualityGateDefinition(
                "Vestige default",
                List.of(
                        new GateCondition(ConditionType.NEW_CRITICAL_ISSUES, 0),
                        new GateCondition(ConditionType.NEW_ISSUES_TOTAL, 5),
                        new GateCondition(ConditionType.REOPENED_ISSUES, 0),
                        new GateCondition(ConditionType.TOTAL_BLOCKER_ISSUES, 0)));
    }
}
