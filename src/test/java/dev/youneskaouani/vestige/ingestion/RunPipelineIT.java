package dev.youneskaouani.vestige.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.service.RunIngestionService;
import dev.youneskaouani.vestige.ingestion.worker.RunProcessingService;
import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.domain.FindingRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.issues.domain.MatchRung;
import dev.youneskaouani.vestige.support.AbstractIntegrationTest;
import dev.youneskaouani.vestige.tenancy.domain.Organization;
import dev.youneskaouani.vestige.tenancy.domain.OrganizationRepository;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ingests two reports for the same branch and asserts §3's central claim survives persistence: the
 * same defect, after the code beneath it has moved, is the <em>same issue</em>.
 *
 * <p>This covers the one gap every other suite left open. {@code IssueMatcherTest} and the corpus
 * harness exercise the matcher as a pure function over in-memory candidates, and the other ITs
 * write their rows by hand - so nothing anywhere drove a SARIF report through parse, persist, match
 * and gate against a real database, and nothing at all ran a <em>second</em> report over a branch
 * that already had issues on it. That second run is the only thing that reads back what the first
 * one wrote, which is why a whole class of write-that-never-happened bugs was invisible: the first
 * run always looked perfect.
 */
@Tag("integration")
class RunPipelineIT extends AbstractIntegrationTest {

    private static final String SQL_INJECTION = "java:S3649";
    private static final String RESOURCE_LEAK = "java:S2095";

    @Autowired private RunIngestionService ingestionService;
    @Autowired private RunProcessingService processingService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID organizationId;

    @BeforeEach
    void seedOrganization() {
        organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);
        String slug = "pipeline-" + organizationId;
        organizationRepository.save(new Organization(organizationId, slug, slug, Instant.now()));
    }

    /**
     * Drops the outbox rows this test's submissions enqueued. Ingestion always writes one (§4.2),
     * but these tests deliberately drive {@link RunProcessingService} themselves and never let a
     * worker claim it, so the row would otherwise stay runnable forever - and {@code
     * OutboxSkipLockedConcurrencyIT} claims across every tenant by design, so it would drain these
     * too and count more jobs than it seeded.
     */
    @AfterEach
    void clearTenant() {
        jdbcTemplate.update("delete from analysis_job where organization_id = ?", organizationId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("every finding of a processed run is linked back to the issue it opened")
    void processingLinksEachFindingToItsIssue() {
        UUID runId = ingestAndProcess("c0ffee01", firstReport());

        List<Finding> findings = findingRepository.findAllByAnalysisRunIdOrderBySeq(runId);
        assertThat(findings).hasSize(2);
        // The regression this exists for: the rows inserted cleanly with issue_id left null,
        // because the entities the tracker mutated were detached copies.
        assertThat(findings).allSatisfy(finding -> assertThat(finding.getIssueId()).isNotNull());
        assertThat(findings).allSatisfy(f -> assertThat(f.getMatchRung()).isEqualTo(MatchRung.NEW));

        // The exact lookup IssueTrackingService#candidateFor does on the next run, and the one that
        // threw "has no finding for its own last_seen_run_id" before the fix.
        assertThat(issueRepository.findAll())
                .hasSize(2)
                .allSatisfy(
                        issue ->
                                assertThat(
                                                findingRepository.findByIssueIdAndAnalysisRunId(
                                                        issue.getId(), issue.getLastSeenRunId()))
                                        .isPresent());
    }

    @Test
    @DisplayName(
            "a second run whose code has moved reopens no issue as new - the identity ladder holds"
                    + " through the database")
    void aSecondRunMatchesThroughTheDatabaseRatherThanOpeningDuplicates() {
        ingestAndProcess("c0ffee01", firstReport());
        Map<String, UUID> afterFirst = issueIdsByRule();
        assertThat(afterFirst).containsOnlyKeys(SQL_INJECTION, RESOURCE_LEAK);

        // Same two defects, both moved down the file, one with its enclosing method renamed and its
        // local rebound - plus one genuinely new defect in a different file.
        UUID secondRunId = ingestAndProcess("c0ffee02", secondReport());

        List<Issue> issues = issueRepository.findAll();
        assertThat(issues).hasSize(3);

        Map<String, UUID> afterSecond = issueIdsByRule();
        // The two carried-over issues keep their identity rather than being re-opened as new.
        assertThat(afterSecond.get(RESOURCE_LEAK)).isEqualTo(afterFirst.get(RESOURCE_LEAK));
        assertThat(issues)
                .filteredOn(issue -> issue.getId().equals(afterFirst.get(RESOURCE_LEAK)))
                .singleElement()
                .satisfies(
                        issue -> {
                            assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
                            assertThat(issue.getLastSeenRunId()).isEqualTo(secondRunId);
                            assertThat(issue.getStartLine()).isEqualTo(104);
                        });

        // Nothing was auto-resolved: a false split would show up here as a RESOLVED_FIXED issue
        // alongside a brand-new duplicate of it.
        assertThat(issues).noneMatch(issue -> issue.getStatus() == IssueStatus.RESOLVED_FIXED);

        List<Finding> secondFindings =
                findingRepository.findAllByAnalysisRunIdOrderBySeq(secondRunId);
        assertThat(secondFindings).hasSize(3);
        assertThat(secondFindings).allSatisfy(f -> assertThat(f.getIssueId()).isNotNull());
        assertThat(secondFindings).filteredOn(f -> f.getMatchRung() == MatchRung.NEW).hasSize(1);
    }

    private Map<String, UUID> issueIdsByRule() {
        return issueRepository.findAll().stream()
                .collect(Collectors.toMap(Issue::getRuleId, Issue::getId, (a, b) -> a));
    }

    private UUID ingestAndProcess(String commitSha, String sarif) {
        RunIngestionService.SubmissionResult submission =
                ingestionService.submit(
                        organizationId,
                        "github",
                        "acme",
                        "widgets",
                        "main",
                        commitSha,
                        null,
                        null,
                        sarif.getBytes(StandardCharsets.UTF_8),
                        Instant.now());
        AnalysisRun run = submission.run();
        // Driven directly rather than through OutboxWorker's poll: what is under test is the
        // parse/match/persist path, not the scheduling around it, and the assertions want a
        // definite point at which it has finished.
        processingService.process(run.getId(), Instant.now());
        return run.getId();
    }

    private static String firstReport() {
        return report(
                result(
                        SQL_INJECTION,
                        "error",
                        "PaymentService.java",
                        42,
                        "String sql = \\\"SELECT * FROM refunds WHERE id = \\\" + o.getId();",
                        "com.acme.PaymentService#refund"),
                result(
                        RESOURCE_LEAK,
                        "warning",
                        "ReportExporter.java",
                        88,
                        "InputStream in = new FileInputStream(path);",
                        "com.acme.ReportExporter#export"));
    }

    private static String secondReport() {
        return report(
                // Moved 16 lines down, enclosing method renamed, local rebound: rung 1 is gone,
                // rung 2 (normalised line hash) is what has to catch this.
                result(
                        SQL_INJECTION,
                        "error",
                        "PaymentService.java",
                        58,
                        "String sql = \\\"SELECT * FROM refunds WHERE id = \\\" + order.getId();",
                        "com.acme.PaymentService#issueRefund"),
                // Moved, same symbol: rung 1 still holds.
                result(
                        RESOURCE_LEAK,
                        "warning",
                        "ReportExporter.java",
                        104,
                        "InputStream in = new FileInputStream(target);",
                        "com.acme.ReportExporter#export"),
                result(
                        SQL_INJECTION,
                        "error",
                        "InvoiceService.java",
                        17,
                        "String sql = \\\"DELETE FROM invoices WHERE ref = \\\" + ref;",
                        "com.acme.InvoiceService#purge"));
    }

    private static String report(String... results) {
        return """
        {
          "version": "2.1.0",
          "runs": [
            {
              "tool": { "driver": { "name": "SonarQube", "version": "10.6" } },
              "results": [%s]
            }
          ]
        }
        """
                .formatted(String.join(",", results));
    }

    private static String result(
            String ruleId, String level, String file, int line, String snippet, String symbol) {
        return """
        {
          "ruleId": "%s",
          "level": "%s",
          "message": { "text": "finding in %s" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": { "uri": "src/main/java/com/acme/%s" },
                "region": { "startLine": %d, "snippet": { "text": "%s" } }
              },
              "logicalLocations": [ { "fullyQualifiedName": "%s" } ]
            }
          ]
        }
        """
                .formatted(ruleId, level, file, file, line, snippet, symbol);
    }
}
