package dev.youneskaouani.vestige.gate.domain;

import dev.youneskaouani.vestige.common.domain.Severity;
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
     * The gate a project gets until someone configures one.
     *
     * <p>It is scoped to changed lines on purpose. A gate that fails a pull request for debt the
     * author did not write is a gate that teams route around, and "leave the campsite cleaner than
     * you found it" is the only policy that survives contact with an existing codebase.
     */
    public static QualityGateDefinition defaultGate() {
        return new QualityGateDefinition(
                "Vestige default",
                List.of(
                        GateCondition.noNewIssuesAtOrAbove(Severity.BLOCKER, true),
                        GateCondition.maxNewIssues(10),
                        GateCondition.noReopenedIssues()));
    }
}
