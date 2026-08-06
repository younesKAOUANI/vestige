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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QualityGateEvaluatorTest {

    private final QualityGateEvaluator evaluator = new QualityGateEvaluator();

    private static GateInput.GateIssue issue(
            String id, Severity severity, boolean isNew, boolean onChangedLine) {
        return new GateInput.GateIssue(id, severity, IssueStatus.OPEN, isNew, false, onChangedLine);
    }

    @Test
    @DisplayName("passes when there is nothing to complain about")
    void passesOnAQuietRun() {
        GateOutcome outcome = evaluator.evaluate(
                QualityGateDefinition.defaultGate(),
                new GateInput(List.of(issue("old-1", Severity.BLOCKER, false, false))));

        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.summary()).isEqualTo("Vestige default passed (3 conditions)");
    }

    @Test
    @DisplayName("fails on a new blocker that sits on a changed line")
    void failsOnNewBlockerOnChangedLine() {
        GateOutcome outcome = evaluator.evaluate(
                QualityGateDefinition.defaultGate(),
                new GateInput(List.of(issue("new-1", Severity.BLOCKER, true, true))));

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .containsExactly(ConditionType.NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY);
        assertThat(outcome.failures().get(0).offendingIssueIds()).containsExactly("new-1");
        assertThat(outcome.failures().get(0).explain())
                .isEqualTo("no new issues of severity >= BLOCKER on changed lines: FAIL (actual 1, threshold 0)");
    }

    @Test
    @DisplayName("ignores a new blocker on a line the pull request did not touch")
    void ignoresUntouchedLinesWhenScoped() {
        GateOutcome outcome = evaluator.evaluate(
                QualityGateDefinition.defaultGate(),
                new GateInput(List.of(issue("new-1", Severity.BLOCKER, true, false))));

        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("counts every new issue when the condition is not scoped to changed lines")
    void countsAllLinesWhenUnscoped() {
        QualityGateDefinition strict = new QualityGateDefinition(
                "strict", List.of(GateCondition.noNewIssuesAtOrAbove(Severity.MAJOR, false)));

        GateOutcome outcome = evaluator.evaluate(
                strict, new GateInput(List.of(issue("new-1", Severity.MAJOR, true, false))));

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
    }

    @Test
    @DisplayName("treats the severity threshold as at-or-above, not equal-to")
    void thresholdIsInclusiveAndOrdered() {
        QualityGateDefinition gate = new QualityGateDefinition(
                "critical+", List.of(GateCondition.noNewIssuesAtOrAbove(Severity.CRITICAL, false)));

        assertThat(evaluator
                        .evaluate(gate, new GateInput(List.of(issue("a", Severity.CRITICAL, true, false))))
                        .status())
                .isEqualTo(GateStatus.FAIL);
        assertThat(evaluator
                        .evaluate(gate, new GateInput(List.of(issue("b", Severity.BLOCKER, true, false))))
                        .status())
                .isEqualTo(GateStatus.FAIL);
        assertThat(evaluator
                        .evaluate(gate, new GateInput(List.of(issue("c", Severity.MAJOR, true, false))))
                        .status())
                .isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("fails when the new issue count exceeds the budget, and not before")
    void enforcesNewIssueBudget() {
        QualityGateDefinition gate =
                new QualityGateDefinition("budget", List.of(GateCondition.maxNewIssues(2)));

        GateInput justEnough = new GateInput(List.of(
                issue("a", Severity.INFO, true, false), issue("b", Severity.INFO, true, false)));
        GateInput oneTooMany = new GateInput(List.of(
                issue("a", Severity.INFO, true, false),
                issue("b", Severity.INFO, true, false),
                issue("c", Severity.INFO, true, false)));

        assertThat(evaluator.evaluate(gate, justEnough).status()).isEqualTo(GateStatus.PASS);

        GateOutcome failed = evaluator.evaluate(gate, oneTooMany);
        assertThat(failed.status()).isEqualTo(GateStatus.FAIL);
        assertThat(failed.conditions().get(0).actualValue()).isEqualTo(3);
        assertThat(failed.conditions().get(0).threshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("fails when a resolved issue comes back")
    void failsOnReopenedIssues() {
        GateInput input = new GateInput(List.of(new GateInput.GateIssue(
                "old-1", Severity.MINOR, IssueStatus.REOPENED, false, true, false)));

        GateOutcome outcome = evaluator.evaluate(QualityGateDefinition.defaultGate(), input);

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.failures())
                .extracting(c -> c.condition().type())
                .containsExactly(ConditionType.NO_REOPENED_ISSUES);
    }

    @Test
    @DisplayName("never fails on an issue a human already accepted")
    void ignoresTriagedIssues() {
        GateInput accepted = new GateInput(List.of(
                new GateInput.GateIssue(
                        "a", Severity.BLOCKER, IssueStatus.ACCEPTED, true, false, true),
                new GateInput.GateIssue(
                        "b", Severity.BLOCKER, IssueStatus.FALSE_POSITIVE, true, true, true)));

        assertThat(evaluator.evaluate(QualityGateDefinition.defaultGate(), accepted).status())
                .isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("reports every condition, not just the failing ones")
    void reportsEveryCondition() {
        GateOutcome outcome = evaluator.evaluate(
                QualityGateDefinition.defaultGate(),
                new GateInput(List.of(issue("new-1", Severity.BLOCKER, true, true))));

        assertThat(outcome.conditions()).hasSize(3);
        assertThat(outcome.conditions())
                .extracting(c -> c.condition().type())
                .containsExactly(
                        ConditionType.NO_NEW_ISSUES_AT_OR_ABOVE_SEVERITY,
                        ConditionType.MAX_NEW_ISSUES,
                        ConditionType.NO_REOPENED_ISSUES);
        assertThat(outcome.summary()).isEqualTo("Vestige default failed (1 of 3 conditions)");
    }

    @Test
    @DisplayName("caps how many offending issues a failing condition names")
    void capsOffenderList() {
        QualityGateDefinition gate =
                new QualityGateDefinition("zero", List.of(GateCondition.maxNewIssues(0)));
        List<GateInput.GateIssue> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(issue("issue-" + i, Severity.INFO, true, false));
        }

        GateOutcome outcome = evaluator.evaluate(gate, new GateInput(many));

        assertThat(outcome.conditions().get(0).actualValue()).isEqualTo(100);
        assertThat(outcome.conditions().get(0).offendingIssueIds()).hasSize(25);
    }
}
