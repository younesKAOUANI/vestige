package dev.youneskaouani.vestige.gate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.gate.domain.ConditionType;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.GateInput;
import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import dev.youneskaouani.vestige.gate.domain.GateStatus;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import dev.youneskaouani.vestige.gate.service.QualityGateEvaluator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QualityGateEvaluatorTest {

    private final QualityGateEvaluator evaluator = new QualityGateEvaluator();

    private static GateInput.GateIssue issue(String id, Severity severity, boolean isNew) {
        return new GateInput.GateIssue(id, severity, IssueStatus.OPEN, isNew, false);
    }

    @Test
    @DisplayName("passes when there is nothing to complain about")
    void passesOnAQuietRun() {
        GateOutcome outcome =
                evaluator.evaluate(QualityGateDefinition.defaultGate(), new GateInput(List.of()));

        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.summary()).isEqualTo("Vestige default passed (4 conditions)");
    }

    @Test
    @DisplayName("fails on a new critical issue, regardless of which line it sits on")
    void failsOnNewCriticalIssue() {
        GateOutcome outcome =
                evaluator.evaluate(
                        QualityGateDefinition.defaultGate(),
                        new GateInput(List.of(issue("new-1", Severity.CRITICAL, true))));

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .containsExactly(ConditionType.NEW_CRITICAL_ISSUES);
        assertThat(outcome.failures().get(0).offendingIssueIds()).containsExactly("new-1");
        assertThat(outcome.failures().get(0).explain())
                .isEqualTo("new issues of severity >= CRITICAL <= 0: FAIL (actual 1, threshold 0)");
    }

    @Test
    @DisplayName(
            "a blocker also satisfies the critical-or-above condition, because it is at least as bad")
    void blockerCountsAsCriticalOrAbove() {
        GateOutcome outcome =
                evaluator.evaluate(
                        QualityGateDefinition.defaultGate(),
                        new GateInput(List.of(issue("new-1", Severity.BLOCKER, true))));

        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .contains(ConditionType.NEW_CRITICAL_ISSUES, ConditionType.TOTAL_BLOCKER_ISSUES);
    }

    @Test
    @DisplayName("fails when the new issue count exceeds the budget, and not before")
    void enforcesNewIssueBudget() {
        QualityGateDefinition gate =
                new QualityGateDefinition(
                        "budget", List.of(new GateCondition(ConditionType.NEW_ISSUES_TOTAL, 2)));

        GateInput justEnough =
                new GateInput(
                        List.of(issue("a", Severity.INFO, true), issue("b", Severity.INFO, true)));
        GateInput oneTooMany =
                new GateInput(
                        List.of(
                                issue("a", Severity.INFO, true),
                                issue("b", Severity.INFO, true),
                                issue("c", Severity.INFO, true)));

        assertThat(evaluator.evaluate(gate, justEnough).status()).isEqualTo(GateStatus.PASS);

        GateOutcome failed = evaluator.evaluate(gate, oneTooMany);
        assertThat(failed.status()).isEqualTo(GateStatus.FAIL);
        assertThat(failed.conditions().get(0).actualValue()).isEqualTo(3);
        assertThat(failed.conditions().get(0).threshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("fails when a resolved issue comes back")
    void failsOnReopenedIssues() {
        GateInput input =
                new GateInput(
                        List.of(
                                new GateInput.GateIssue(
                                        "old-1",
                                        Severity.MINOR,
                                        IssueStatus.REOPENED,
                                        false,
                                        true)));

        GateOutcome outcome = evaluator.evaluate(QualityGateDefinition.defaultGate(), input);

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .containsExactly(ConditionType.REOPENED_ISSUES);
    }

    @Test
    @DisplayName(
            "total blocker count looks at every outstanding issue, not just this run's new ones")
    void totalBlockerScopeIsTheWholeProject() {
        GateInput input =
                new GateInput(
                        List.of(
                                new GateInput.GateIssue(
                                        "old-blocker",
                                        Severity.BLOCKER,
                                        IssueStatus.OPEN,
                                        false,
                                        false)));

        GateOutcome outcome = evaluator.evaluate(QualityGateDefinition.defaultGate(), input);

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .containsExactly(ConditionType.TOTAL_BLOCKER_ISSUES);
    }

    @Test
    @DisplayName("never fails on an issue a human already triaged away")
    void ignoresTriagedIssues() {
        GateInput accepted =
                new GateInput(
                        List.of(
                                new GateInput.GateIssue(
                                        "a",
                                        Severity.BLOCKER,
                                        IssueStatus.RESOLVED_WONT_FIX,
                                        true,
                                        false),
                                new GateInput.GateIssue(
                                        "b",
                                        Severity.BLOCKER,
                                        IssueStatus.RESOLVED_FALSE_POSITIVE,
                                        true,
                                        true)));

        assertThat(evaluator.evaluate(QualityGateDefinition.defaultGate(), accepted).status())
                .isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("reports every condition, not just the failing ones")
    void reportsEveryCondition() {
        GateOutcome outcome =
                evaluator.evaluate(
                        QualityGateDefinition.defaultGate(),
                        new GateInput(List.of(issue("new-1", Severity.CRITICAL, true))));

        assertThat(outcome.conditions()).hasSize(4);
        assertThat(outcome.conditions())
                .extracting(c -> c.condition().type())
                .containsExactly(
                        ConditionType.NEW_CRITICAL_ISSUES,
                        ConditionType.NEW_ISSUES_TOTAL,
                        ConditionType.REOPENED_ISSUES,
                        ConditionType.TOTAL_BLOCKER_ISSUES);
        assertThat(outcome.summary()).isEqualTo("Vestige default failed (1 of 4 conditions)");
    }

    @Test
    @DisplayName("caps how many offending issues a failing condition names")
    void capsOffenderList() {
        QualityGateDefinition gate =
                new QualityGateDefinition(
                        "zero", List.of(new GateCondition(ConditionType.NEW_ISSUES_TOTAL, 0)));
        List<GateInput.GateIssue> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(issue("issue-" + i, Severity.INFO, true));
        }

        GateOutcome outcome = evaluator.evaluate(gate, new GateInput(many));

        assertThat(outcome.conditions().get(0).actualValue()).isEqualTo(100);
        assertThat(outcome.conditions().get(0).offendingIssueIds()).hasSize(25);
    }
}
