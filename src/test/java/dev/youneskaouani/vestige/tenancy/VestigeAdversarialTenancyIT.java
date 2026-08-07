package dev.youneskaouani.vestige.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.Severity;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §5.2's "deliberate adversarial case": a repository method with the tenant filter <em>removed</em>
 * must still return zero foreign rows. {@link IssueRepository#findAll()} is exactly that query -
 * Spring Data generates it with no {@code WHERE} clause at all, tenant or otherwise - which makes
 * it the most honest adversary available: not a hand-written query that merely forgot the
 * predicate, but the one built-in method that structurally cannot have one. If row-level security
 * in Postgres is doing its job, that omission still cannot leak another organisation's rows.
 *
 * <p>Real Postgres, not H2: {@code FORCE ROW LEVEL SECURITY} is what this test exists to prove, and
 * H2 does not implement it - see {@link AbstractIntegrationTest}.
 */
@Tag("integration")
class VestigeAdversarialTenancyIT extends AbstractIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;

    @Autowired private ProjectRepository projectRepository;

    @Autowired private BranchRepository branchRepository;

    @Autowired private AnalysisRunRepository analysisRunRepository;

    @Autowired private IssueRepository issueRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "findAll() - no tenant predicate of its own - still shows only the current tenant's rows")
    void aQueryWithNoTenantFilterStillSeesOnlyItsOwnTenant() {
        Issue issueA = seedOrganizationWithOneIssue("org-a-" + UUID.randomUUID());
        Issue issueB = seedOrganizationWithOneIssue("org-b-" + UUID.randomUUID());

        TenantContext.set(issueA.getOrganizationId());
        List<UUID> visibleToA = idsOf(issueRepository.findAll());
        assertThat(visibleToA).contains(issueA.getId()).doesNotContain(issueB.getId());

        TenantContext.set(issueB.getOrganizationId());
        List<UUID> visibleToB = idsOf(issueRepository.findAll());
        assertThat(visibleToB).contains(issueB.getId()).doesNotContain(issueA.getId());
    }

    @Test
    @DisplayName("no tenant set at all fails closed: zero rows, never every row")
    void noTenantContextSeesNothing() {
        seedOrganizationWithOneIssue("org-only-" + UUID.randomUUID());

        TenantContext.clear();

        assertThat(issueRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("findById also refuses a foreign row rather than merely omitting it from a list")
    void findByIdOfAForeignRowIsAbsent() {
        Issue foreign = seedOrganizationWithOneIssue("org-foreign-" + UUID.randomUUID());

        TenantContext.set(UUID.randomUUID()); // a third organisation that owns nothing

        assertThat(issueRepository.findById(foreign.getId())).isEmpty();
    }

    private static List<UUID> idsOf(List<Issue> issues) {
        return issues.stream().map(Issue::getId).toList();
    }

    /**
     * Creates a whole organisation, under its own {@link TenantContext}, with exactly one issue.
     */
    private Issue seedOrganizationWithOneIssue(String slug) {
        UUID organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);
        Instant now = Instant.now();

        organizationRepository.save(new Organization(organizationId, slug, slug, now));
        Project project =
                projectRepository.save(
                        new Project(
                                UUID.randomUUID(),
                                organizationId,
                                "github",
                                "acme",
                                slug,
                                "main",
                                now));
        Branch branch =
                branchRepository.save(
                        new Branch(
                                UUID.randomUUID(),
                                organizationId,
                                project.getId(),
                                "main",
                                true,
                                now));
        AnalysisRun run =
                analysisRunRepository.save(
                        new AnalysisRun(
                                UUID.randomUUID(),
                                organizationId,
                                project.getId(),
                                branch.getId(),
                                "abc123",
                                null,
                                "ESLint",
                                "8.0.0",
                                "digest-" + slug,
                                null,
                                now));

        return issueRepository.save(
                new Issue(
                        UUID.randomUUID(),
                        organizationId,
                        project.getId(),
                        branch.getId(),
                        "java:S1234",
                        Severity.MAJOR,
                        "Do not do that",
                        "src/main/java/Foo.java",
                        null,
                        10,
                        run.getId(),
                        "abc123",
                        now));
    }
}
