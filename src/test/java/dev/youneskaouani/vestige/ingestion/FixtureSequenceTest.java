package dev.youneskaouani.vestige.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.IssueLifecycle;
import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.ingestion.sarif.AnalysisReport;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import dev.youneskaouani.vestige.matching.CandidateFinding;
import dev.youneskaouani.vestige.matching.DiffModel;
import dev.youneskaouani.vestige.matching.IssueMatch;
import dev.youneskaouani.vestige.matching.IssueMatcher;
import dev.youneskaouani.vestige.matching.MatchRequest;
import dev.youneskaouani.vestige.matching.MatchResult;
import dev.youneskaouani.vestige.matching.MatchStrategy;
import dev.youneskaouani.vestige.matching.TrackedIssue;
import dev.youneskaouani.vestige.matching.UnifiedDiffParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replays the whole fixture sequence through the reader, the matcher and the lifecycle rules.
 *
 * <p>This is the end-to-end proof that the pieces fit: four successive commits of one file, and an
 * issue that is opened, tracked through a refactor, resolved by a fix and then reopened by a
 * regression. It is also what {@code scripts/demo.sh} shows a reviewer, run against the same files,
 * so the demo cannot claim something the tests do not.
 */
class FixtureSequenceTest {

    private final SarifReader reader = new SarifReader(new ObjectMapper());
    private final IssueMatcher matcher = new IssueMatcher();

    /** Mutable issue state, standing in for the rows the tracking service would persist. */
    private final Map<String, TrackedIssue> issues = new LinkedHashMap<>();
    private final Map<String, Integer> firstSeenRun = new LinkedHashMap<>();
    private int nextIssueNumber = 1;
    private int runNumber;

    @Test
    @DisplayName("opens, tracks, resolves and reopens an issue across four commits")
    void replaysTheFixtureSequence() {
        Outcome first = ingest("commit-01-initial.sarif.json", null);
        assertThat(first.opened()).hasSize(3);
        assertThat(statuses()).containsOnlyKeys("ISSUE-1", "ISSUE-2", "ISSUE-3");
        assertThat(statuses().values()).containsOnly(IssueStatus.OPEN);

        Outcome second = ingest(
                "commit-02-audit-log-refactor.sarif.json",
                "diffs/01-initial--to--02-audit-log-refactor.diff");
        // The null dereference and the arithmetic issue are tracked; the swallowed exception is
        // gone with the method that held it; the new latest() dereference is a new issue.
        assertThat(second.matchedIssueIds()).containsExactlyInAnyOrder("ISSUE-1", "ISSUE-2");
        assertThat(second.resolvedIssueIds()).containsExactly("ISSUE-3");
        assertThat(second.opened()).containsExactly("ISSUE-4");
        assertThat(second.strategyOf("ISSUE-2")).isEqualTo(MatchStrategy.DIFF_REMAPPED_LINE);
        assertThat(second.strategyOf("ISSUE-1")).isEqualTo(MatchStrategy.STRUCTURAL_HASH);

        Outcome third = ingest(
                "commit-03-null-safety-fix.sarif.json",
                "diffs/02-audit-log-refactor--to--03-null-safety-fix.diff");
        assertThat(third.matchedIssueIds()).containsExactly("ISSUE-2");
        assertThat(third.resolvedIssueIds()).containsExactlyInAnyOrder("ISSUE-1", "ISSUE-4");
        assertThat(third.opened()).isEmpty();
        assertThat(statuses())
                .containsEntry("ISSUE-1", IssueStatus.RESOLVED)
                .containsEntry("ISSUE-2", IssueStatus.OPEN)
                .containsEntry("ISSUE-4", IssueStatus.RESOLVED);

        Outcome fourth = ingest(
                "commit-04-regression.sarif.json",
                "diffs/03-null-safety-fix--to--04-regression.diff");
        assertThat(fourth.opened()).as("a regression must reopen, not open a clone").isEmpty();
        assertThat(fourth.reopenedIssueIds()).containsExactly("ISSUE-1");
        assertThat(statuses())
                .containsEntry("ISSUE-1", IssueStatus.REOPENED)
                .containsEntry("ISSUE-2", IssueStatus.OPEN)
                .containsEntry("ISSUE-3", IssueStatus.RESOLVED)
                .containsEntry("ISSUE-4", IssueStatus.RESOLVED);

        // Nothing was ever duplicated: four issues, four commits, one identity each.
        assertThat(issues).hasSize(4);
        assertThat(firstSeenRun).containsEntry("ISSUE-1", 1).containsEntry("ISSUE-4", 2);
    }

    /** What one ingested report did to the issue set. */
    private record Outcome(
            List<String> opened,
            List<String> matchedIssueIds,
            List<String> resolvedIssueIds,
            List<String> reopenedIssueIds,
            Map<String, MatchStrategy> strategies) {

        MatchStrategy strategyOf(String issueId) {
            return strategies.get(issueId);
        }
    }

    private Outcome ingest(String reportName, String diffName) {
        runNumber++;
        AnalysisReport report = reader.read(SarifFixtures.bytes(reportName));
        DiffModel diff = diffName == null
                ? DiffModel.empty()
                : UnifiedDiffParser.parse(SarifFixtures.text(diffName));

        List<TrackedIssue> previous = List.copyOf(issues.values());
        MatchResult result = matcher.match(new MatchRequest(previous, report.findings(), diff));

        List<String> opened = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        List<String> reopened = new ArrayList<>();
        Map<String, MatchStrategy> strategies = new LinkedHashMap<>();

        for (IssueMatch match : result.matches()) {
            TrackedIssue before = match.issue();
            IssueStatus after = IssueLifecycle.afterSighting(before.status());
            if (IssueLifecycle.isReopening(before.status(), after)) {
                reopened.add(before.id());
            }
            matched.add(before.id());
            strategies.put(before.id(), match.strategy());
            issues.put(before.id(), reseat(before, after, match.finding()));
        }

        for (TrackedIssue gone : result.disappearedIssues()) {
            IssueStatus after = IssueLifecycle.afterDisappearance(gone.status());
            if (after != gone.status()) {
                resolved.add(gone.id());
            }
            issues.put(gone.id(), reseat(gone, after, null));
        }

        for (CandidateFinding fresh : result.newFindings()) {
            String id = "ISSUE-" + nextIssueNumber++;
            opened.add(id);
            firstSeenRun.put(id, runNumber);
            issues.put(
                    id,
                    new TrackedIssue(
                            id,
                            fresh.ruleId(),
                            fresh.severity(),
                            IssueStatus.OPEN,
                            fresh.location(),
                            fresh.fingerprints()));
        }

        return new Outcome(opened, matched, resolved, reopened, strategies);
    }

    /** Moves an issue onto its newest occurrence, which is what the next run will match against. */
    private static TrackedIssue reseat(TrackedIssue issue, IssueStatus status, CandidateFinding sighting) {
        if (sighting == null) {
            return new TrackedIssue(
                    issue.id(), issue.ruleId(), issue.severity(), status, issue.location(), issue.fingerprints());
        }
        return new TrackedIssue(
                issue.id(),
                issue.ruleId(),
                sighting.severity(),
                status,
                sighting.location(),
                sighting.fingerprints());
    }

    private Map<String, IssueStatus> statuses() {
        Map<String, IssueStatus> byId = new LinkedHashMap<>();
        issues.forEach((id, issue) -> byId.put(id, issue.status()));
        return byId;
    }
}
