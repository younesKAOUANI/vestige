package dev.youneskaouani.vestige.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.hash.HashChain;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.support.AbstractIntegrationTest;
import dev.youneskaouani.vestige.tenancy.domain.Branch;
import dev.youneskaouani.vestige.tenancy.domain.BranchRepository;
import dev.youneskaouani.vestige.tenancy.domain.Organization;
import dev.youneskaouani.vestige.tenancy.domain.OrganizationRepository;
import dev.youneskaouani.vestige.tenancy.domain.Project;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import dev.youneskaouani.vestige.triage.service.AuditChainVerifier;
import dev.youneskaouani.vestige.triage.service.TriageEventAppender;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §6's tamper-evidence promise, proven against a real Postgres in the two ways it actually holds:
 *
 * <ul>
 *   <li>V4's {@code BEFORE UPDATE OR DELETE} trigger <em>prevents</em> a direct mutation, even
 *       though {@code vestige_app} is granted plain SQL {@code UPDATE}/{@code DELETE} on the table
 *       (V2) - the trigger, not a revoked privilege, is the enforcement;
 *   <li>{@link AuditChainVerifier} <em>detects</em> the one kind of tampering the trigger does not
 *       even try to stop - a row inserted directly, outside {@link TriageEventAppender}, with a
 *       fabricated hash.
 * </ul>
 *
 * <p>Doing the raw mutation through {@link JdbcTemplate} rather than JPA is deliberate: {@code
 * TriageEvent} has no setters at all, so the interesting attacker here is not "application code
 * that made a mistake" but a SQL client that bypassed the application entirely - exactly what the
 * class javadoc on {@code TriageEvent} says this defends against.
 */
@Tag("integration")
class VestigeAuditTamperDetectionIT extends AbstractIntegrationTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AnalysisRunRepository analysisRunRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private TriageEventAppender appender;

    @Autowired
    private AuditChainVerifier verifier;

    @Autowired
    private TriageEventRepository triageEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID issueId;

    @BeforeEach
    void seedIssueAndChain() {
        organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);

        Instant now = Instant.now();
        String slug = "audit-" + organizationId;
        organizationRepository.save(new Organization(organizationId, slug, slug, now));
        Project project = projectRepository.save(
                new Project(UUID.randomUUID(), organizationId, "github", "acme", slug, "main", now));
        Branch branch = branchRepository.save(
                new Branch(UUID.randomUUID(), organizationId, project.getId(), "main", true, now));
        AnalysisRun run = analysisRunRepository.save(new AnalysisRun(
                UUID.randomUUID(), organizationId, project.getId(), branch.getId(), "abc123", null, "ESLint", "8.0.0",
                "digest-" + slug, null, now));
        Issue issue = issueRepository.save(new Issue(
                UUID.randomUUID(), organizationId, project.getId(), branch.getId(), "java:S1234", Severity.MAJOR,
                "Do not do that", "src/main/java/Foo.java", null, 5, run.getId(), "abc123", now));
        issueId = issue.getId();

        appender.append(
                organizationId, issueId, "younes", IssueStatus.OPEN, IssueStatus.RESOLVED_WONT_FIX, "accepted risk", now);
        appender.append(
                organizationId, issueId, "younes", IssueStatus.RESOLVED_WONT_FIX, IssueStatus.OPEN, null, now.plusSeconds(60));
        appender.append(
                organizationId,
                issueId,
                "younes",
                IssueStatus.OPEN,
                IssueStatus.RESOLVED_FALSE_POSITIVE,
                "confirmed false positive",
                now.plusSeconds(120));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a freshly-appended chain verifies intact against a real database")
    void freshChainVerifiesIntact() {
        assertThat(verifier.verify()).isEqualTo(new AuditChainVerifier.VerificationResult.Intact(3));
    }

    @Test
    @DisplayName("V4's trigger blocks a direct UPDATE, even though vestige_app is granted UPDATE on the table")
    void directUpdateIsBlockedByTheTrigger() {
        UUID targetId =
                triageEventRepository.findAllByIssueIdOrderBySequenceNumberAsc(issueId).get(1).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "update triage_event set justification = ? where id = ?", "tampered after the fact", targetId))
                .isInstanceOf(DataAccessException.class);

        assertThat(verifier.verify()).isEqualTo(new AuditChainVerifier.VerificationResult.Intact(3));
    }

    @Test
    @DisplayName("V4's trigger blocks a direct DELETE the same way")
    void directDeleteIsBlockedByTheTrigger() {
        UUID targetId =
                triageEventRepository.findAllByIssueIdOrderBySequenceNumberAsc(issueId).get(0).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from triage_event where id = ?", targetId))
                .isInstanceOf(DataAccessException.class);

        assertThat(triageEventRepository.findAllByIssueIdOrderBySequenceNumberAsc(issueId)).hasSize(3);
        assertThat(verifier.verify()).isEqualTo(new AuditChainVerifier.VerificationResult.Intact(3));
    }

    @Test
    @DisplayName("a row inserted directly, bypassing TriageEventAppender's hashing, is caught on verify")
    void aFabricatedInsertIsCaughtOnVerify() {
        // INSERT is not among the operations V4's trigger guards - only UPDATE/DELETE, since an
        // append-only log's whole point is that new rows are always allowed. A fabricated insert is
        // exactly the case AuditChainVerifier exists for instead.
        jdbcTemplate.update(
                "insert into triage_event (id, organization_id, issue_id, sequence_number, actor, from_status, "
                        + "to_status, justification, occurred_at, prev_hash, entry_hash) values "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                organizationId,
                issueId,
                4L,
                "not-the-appender",
                IssueStatus.OPEN.name(),
                IssueStatus.RESOLVED_WONT_FIX.name(),
                "inserted directly, hash made up",
                Timestamp.from(Instant.now()),
                HashChain.GENESIS_HASH,
                "1".repeat(64));

        AuditChainVerifier.VerificationResult result = verifier.verify();

        assertThat(result).isEqualTo(new AuditChainVerifier.VerificationResult.Broken(3));
    }
}
