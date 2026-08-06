package dev.youneskaouani.vestige.ingestion.worker;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJob;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJobRepository;
import dev.youneskaouani.vestige.tenancy.web.WorkerContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims and leases the next runnable job (§4.2), and nothing past that: which organisation the
 * job belongs to is only known once this returns, so anything about processing it is
 * deliberately out of scope here - see {@link OutboxWorker}.
 *
 * <p>A dedicated {@code @Transactional} method, not folded into {@link OutboxWorker} directly, for
 * two reasons that both matter: {@link OutboxWorker#pollOnce()} is not itself transactional (it
 * spans the claim, the run, and the outcome - three separate transactions on purpose, since a
 * failure in the second must not roll back the first), and {@link WorkerContext#runEscalated} has
 * to be active no later than the moment {@link AnalysisJobRepository#claimNextRunnable} first
 * checks out a connection - which, for a Spring/Hibernate connection acquired lazily on first
 * statement (see {@code TenantRoutingDataSource}'s own javadoc), is exactly what wrapping the body
 * of this single {@code @Transactional} method in {@code runEscalated} guarantees.
 */
@Service
public class JobLeaseService {

    private final AnalysisJobRepository jobRepository;
    private final VestigeProperties properties;

    public JobLeaseService(AnalysisJobRepository jobRepository, VestigeProperties properties) {
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    @Transactional
    public Optional<ClaimedJob> claimAndLease(Instant now) {
        return WorkerContext.runEscalated(() -> {
            List<UUID> claimable = jobRepository.claimNextRunnable(now);
            if (claimable.isEmpty()) {
                return Optional.empty();
            }
            AnalysisJob job = jobRepository
                    .findById(claimable.get(0))
                    .orElseThrow(() -> new IllegalStateException(
                            "Job " + claimable.get(0) + " was just claimed but is now missing"));
            job.lease(now, properties.worker().leaseDuration());
            return Optional.of(
                    new ClaimedJob(job.getId(), job.getOrganizationId(), job.getAnalysisRunId(), job.getAttemptCount()));
        });
    }
}
