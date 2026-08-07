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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims and leases the next runnable job (§4.2), and nothing past that: which organisation the job
 * belongs to is only known once this returns, so anything about processing it is deliberately out
 * of scope here - see {@link OutboxWorker}.
 *
 * <p>A dedicated method, not folded into {@link OutboxWorker} directly, because {@link
 * OutboxWorker#pollOnce()} is not itself transactional: it spans the claim, the run, and the
 * outcome as three separate transactions on purpose, since a failure in the second must not roll
 * back the first.
 *
 * <p><b>Why a {@link TransactionTemplate} rather than {@code @Transactional} on this method.</b>
 * The escalation has to already be active when the transaction acquires its connection, because
 * that checkout is the moment {@code TenantRoutingDataSource} publishes {@code vestige.worker} into
 * the database session - and the value it publishes then is the value the {@code analysis_job}
 * policy sees for the whole transaction. Spring's transaction manager acquires that connection
 * during {@code doBegin}, before any code in the method body runs, so a method annotated
 * {@code @Transactional} whose body opens with {@code runEscalated} escalates strictly too late:
 * the session was already told {@code vestige.worker = off}, the policy matches nothing, and the
 * worker quietly claims zero jobs forever rather than failing. Driving the transaction explicitly
 * puts {@code runEscalated} unambiguously outside it, which is the ordering this depends on.
 */
@Service
public class JobLeaseService {

    private final AnalysisJobRepository jobRepository;
    private final VestigeProperties properties;
    private final TransactionTemplate transactionTemplate;

    public JobLeaseService(
            AnalysisJobRepository jobRepository,
            VestigeProperties properties,
            TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public Optional<ClaimedJob> claimAndLease(Instant now) {
        return WorkerContext.runEscalated(
                () ->
                        transactionTemplate.execute(
                                status -> {
                                    List<UUID> claimable = jobRepository.claimNextRunnable(now);
                                    if (claimable.isEmpty()) {
                                        return Optional.empty();
                                    }
                                    AnalysisJob job =
                                            jobRepository
                                                    .findById(claimable.get(0))
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "Job "
                                                                                    + claimable.get(
                                                                                            0)
                                                                                    + " was just claimed but is now missing"));
                                    job.lease(now, properties.worker().leaseDuration());
                                    return Optional.of(
                                            new ClaimedJob(
                                                    job.getId(),
                                                    job.getOrganizationId(),
                                                    job.getAnalysisRunId(),
                                                    job.getAttemptCount()));
                                }));
    }
}
