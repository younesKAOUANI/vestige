package dev.youneskaouani.vestige.gate.domain;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import java.util.List;

/**
 * The state of one run's issues, as the gate sees it.
 *
 * <p>Deliberately a flat value object rather than the persistent entities: the evaluator is then a
 * pure function of it, which is what makes gate behaviour testable without a database and
 * explainable to the developer whose pull request it just failed.
 */
public record GateInput(List<GateIssue> issues) {

    public GateInput {
        issues = List.copyOf(issues);
    }

    /**
     * One issue as of this run.
     *
     * @param issueId the issue's identifier, so a failing condition can name what failed it
     * @param severity the issue's severity
     * @param status the issue's status after this run was processed
     * @param newInThisRun true when the issue was first seen in this run
     * @param reopenedInThisRun true when a resolved issue was sighted again in this run
     * @param onChangedLine true when the issue sits on a line this run's diff touched
     */
    public record GateIssue(
            String issueId,
            Severity severity,
            IssueStatus status,
            boolean newInThisRun,
            boolean reopenedInThisRun,
            boolean onChangedLine) {

        /**
         * Issues a human has accepted or marked a false positive never fail a gate.
         *
         * <p>Without this, triage would be pointless: the gate would keep failing on a decision the
         * team already took, and the only way out would be to stop running the gate.
         */
        public boolean countsAgainstTheGate() {
            return !status.isSilenced();
        }
    }
}
