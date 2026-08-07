package dev.youneskaouani.vestige.gate.service;

import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.gate.domain.ConditionOutcome;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.GateInput;
import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import dev.youneskaouani.vestige.gate.domain.GateStatus;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Evaluates a quality gate against one run's issues.
 *
 * <p>A pure function: same gate and same input, same verdict, no database and no clock. Every
 * condition reports the number it measured and the threshold it was measured against, so a failing
 * gate is self-explanatory rather than an opaque red cross.
 *
 * <p>"New code" (§7) is whatever the matcher marked {@code newInThisRun} or {@code
 * reopenedInThisRun} on {@link GateInput}; this class never looks at a diff, which is the whole
 * point of ADR-008.
 */
public final class QualityGateEvaluator {

    /** Runs every condition; the gate passes only when all of them do. */
    public GateOutcome evaluate(QualityGateDefinition gate, GateInput input) {
        List<ConditionOutcome> outcomes = new ArrayList<>(gate.conditions().size());
        GateStatus overall = GateStatus.PASS;
        for (GateCondition condition : gate.conditions()) {
            ConditionOutcome outcome = evaluateCondition(condition, input);
            outcomes.add(outcome);
            overall = overall.and(outcome.status());
        }
        return new GateOutcome(gate.name(), overall, outcomes);
    }

    private ConditionOutcome evaluateCondition(GateCondition condition, GateInput input) {
        return switch (condition.type()) {
            case NEW_CRITICAL_ISSUES ->
                    count(
                            condition,
                            input,
                            issue ->
                                    issue.newInThisRun()
                                            && issue.severity().isAtLeast(Severity.CRITICAL));
            case NEW_ISSUES_TOTAL -> count(condition, input, GateInput.GateIssue::newInThisRun);
            case REOPENED_ISSUES -> count(condition, input, GateInput.GateIssue::reopenedInThisRun);
            case TOTAL_BLOCKER_ISSUES ->
                    count(
                            condition,
                            input,
                            issue ->
                                    issue.status().isOutstanding()
                                            && issue.severity().isAtLeast(Severity.BLOCKER));
        };
    }

    private ConditionOutcome count(
            GateCondition condition, GateInput input, Predicate<GateInput.GateIssue> matches) {

        List<String> offenders =
                input.issues().stream()
                        .filter(GateInput.GateIssue::countsAgainstTheGate)
                        .filter(matches)
                        .map(GateInput.GateIssue::issueId)
                        .toList();

        GateStatus status =
                offenders.size() > condition.threshold() ? GateStatus.FAIL : GateStatus.PASS;
        List<String> reported =
                offenders.size() > ConditionOutcome.MAX_REPORTED_ISSUES
                        ? offenders.subList(0, ConditionOutcome.MAX_REPORTED_ISSUES)
                        : offenders;
        return new ConditionOutcome(
                condition,
                status,
                offenders.size(),
                condition.threshold(),
                status == GateStatus.FAIL ? reported : List.of());
    }
}
