package dev.youneskaouani.vestige.ingestion.worker;

import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The polling loop §4.2 describes: {@code POST /runs -> ... -> outbox row -> (worker polls with
 * SKIP LOCKED) -> PARSING -> MATCHING -> gate eval -> COMPLETE}.
 *
 * <p>Deliberately not itself {@code @Transactional} - see {@link JobLeaseService} and {@link
 * JobOutcomeService}'s class javadocs for why claiming, processing, and recording the outcome are
 * three separate transactions rather than one, and why that separation is what makes a mid-run
 * crash recoverable instead of silently losing the job.
 *
 * <p>{@code @Scheduled(fixedDelay = ...)} rather than {@code fixedRate}: the next poll is timed
 * from when the previous one <em>finished</em>, so a slow run (a large SARIF report) does not cause
 * polls to pile up behind it. Spring's default {@code TaskScheduler} runs one thread; running more
 * workers is a matter of raising {@code spring.task.scheduling.pool.size} or deploying more
 * instances, and {@code FOR UPDATE SKIP LOCKED} (§4.2) is exactly what makes either safe without
 * any coordination between them - a property this single-threaded default does not exercise, but
 * does not preclude either.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final JobLeaseService leaseService;
    private final JobOutcomeService outcomeService;
    private final RunProcessingService processingService;

    public OutboxWorker(
            JobLeaseService leaseService,
            JobOutcomeService outcomeService,
            RunProcessingService processingService) {
        this.leaseService = leaseService;
        this.outcomeService = outcomeService;
        this.processingService = processingService;
    }

    @Scheduled(fixedDelayString = "${vestige.worker.poll-interval}")
    public void pollOnce() {
        Optional<ClaimedJob> claimed = leaseService.claimAndLease(Instant.now());
        if (claimed.isEmpty()) {
            return;
        }
        runClaimedJob(claimed.get());
    }

    private void runClaimedJob(ClaimedJob job) {
        TenantContext.set(job.organizationId());
        try {
            processingService.process(job.analysisRunId(), Instant.now());
            outcomeService.recordSuccess(job.jobId(), Instant.now());
        } catch (Exception e) {
            log.warn(
                    "Run {} failed on attempt {} (job {})",
                    job.analysisRunId(),
                    job.attemptCount(),
                    job.jobId(),
                    e);
            outcomeService.recordFailure(
                    job.jobId(),
                    job.organizationId(),
                    job.analysisRunId(),
                    job.attemptCount(),
                    describe(e),
                    Instant.now());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A message worth reading in the UI (§4.2: "surfaced in the UI with the captured stack trace").
     */
    private static String describe(Exception e) {
        String message = e.getMessage();
        String detail =
                (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
        return e.getClass().getSimpleName() + ": " + detail;
    }
}
