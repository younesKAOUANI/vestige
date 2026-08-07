package dev.youneskaouani.vestige.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.ingestion.domain.AnalysisJob;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJobRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.support.AbstractIntegrationTest;
import dev.youneskaouani.vestige.tenancy.domain.Branch;
import dev.youneskaouani.vestige.tenancy.domain.BranchRepository;
import dev.youneskaouani.vestige.tenancy.domain.Organization;
import dev.youneskaouani.vestige.tenancy.domain.OrganizationRepository;
import dev.youneskaouani.vestige.tenancy.domain.Project;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §4.2: "{@code FOR UPDATE SKIP LOCKED} gives safe concurrent workers with no extra
 * infrastructure." This is the test that actually puts several workers on the queue at once and
 * checks the two properties that claim depends on: every job is claimed by <em>somebody</em> (SKIP
 * LOCKED never causes a runnable job to be missed), and no job is ever claimed twice (the row lock
 * genuinely excludes a second claimant, it does not merely make one wait and then also succeed).
 *
 * <p>Drives {@link JobLeaseService#claimAndLease} directly - the real production entry point a
 * worker uses to claim a job - rather than the raw repository query, so this also exercises {@link
 * dev.youneskaouani.vestige.tenancy.web.WorkerContext}'s escalation under real concurrent load.
 */
@Tag("integration")
class OutboxSkipLockedConcurrencyIT extends AbstractIntegrationTest {

    private static final int JOB_COUNT = 20;
    private static final int WORKER_COUNT = 5;

    @Autowired private JobLeaseService leaseService;

    @Autowired private AnalysisJobRepository jobRepository;

    @Autowired private OrganizationRepository organizationRepository;

    @Autowired private ProjectRepository projectRepository;

    @Autowired private BranchRepository branchRepository;

    @Autowired private AnalysisRunRepository analysisRunRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "concurrent workers claim every runnable job exactly once, none twice, none missed")
    void concurrentWorkersClaimEveryJobExactlyOnce() throws Exception {
        SeededJobs seededJobs = seedRunnableJobs(JOB_COUNT);
        Set<UUID> seeded = new HashSet<>(seededJobs.jobIds());

        ExecutorService pool = Executors.newFixedThreadPool(WORKER_COUNT);
        List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < WORKER_COUNT; i++) {
                workers.add(pool.submit(() -> drainQueue(claimed)));
            }
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed).hasSize(JOB_COUNT);
        assertThat(claimed).doesNotHaveDuplicates();
        assertThat(new HashSet<>(claimed)).isEqualTo(seeded);

        // Every seeded job actually reflects one lease attempt, not just a returned id. Reading
        // these
        // back needs the seeding organisation's own tenant context again - the worker escalation
        // the
        // claims themselves used only ever grants access to the queue tables, never to reading them
        // back by id the ordinary way.
        TenantContext.set(seededJobs.organizationId());
        List<AnalysisJob> jobs = jobRepository.findAllById(seeded);
        assertThat(jobs)
                .hasSize(JOB_COUNT)
                .allSatisfy(job -> assertThat(job.getAttemptCount()).isEqualTo(1));
    }

    /** One worker's loop: claim until nothing is left to claim. */
    private void drainQueue(List<UUID> claimed) {
        while (true) {
            Optional<ClaimedJob> job = leaseService.claimAndLease(Instant.now());
            if (job.isEmpty()) {
                return;
            }
            claimed.add(job.get().jobId());
        }
    }

    private record SeededJobs(UUID organizationId, List<UUID> jobIds) {}

    private SeededJobs seedRunnableJobs(int count) {
        UUID organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);
        try {
            Instant now = Instant.now();
            String slug = "skiplocked-" + organizationId;
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

            List<UUID> jobIds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                AnalysisRun run =
                        analysisRunRepository.save(
                                new AnalysisRun(
                                        UUID.randomUUID(),
                                        organizationId,
                                        project.getId(),
                                        branch.getId(),
                                        "commit-" + i,
                                        null,
                                        "ESLint",
                                        "8.0.0",
                                        "digest-" + i,
                                        null,
                                        now));
                AnalysisJob job =
                        jobRepository.save(
                                new AnalysisJob(
                                        UUID.randomUUID(), organizationId, run.getId(), now));
                jobIds.add(job.getId());
            }
            return new SeededJobs(organizationId, jobIds);
        } finally {
            TenantContext.clear();
        }
    }
}
