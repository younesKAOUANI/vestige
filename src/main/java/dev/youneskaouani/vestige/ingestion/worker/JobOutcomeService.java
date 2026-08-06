package dev.youneskaouani.vestige.ingestion.worker;

import dev.youneskaouani.vestige.ingestion.domain.AnalysisJob;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJobRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.ingestion.domain.PoisonReport;
import dev.youneskaouani.vestige.ingestion.domain.PoisonReportRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what happened to a claimed job, in its own transaction, separate from {@code
 * RunProcessingService}'s (§4.2). That separation is not incidental: if processing fails and its
 * transaction rolls back, the failure still has to be durably recorded - a job whose only trace of
 * failing is a log line is a job that silently retries forever, or silently never does.
 *
 * <p>Runs under the job's real {@link dev.youneskaouani.vestige.tenancy.web.TenantContext}
 * (set by {@link OutboxWorker} once {@link JobLeaseService} has revealed which organisation the
 * job belongs to), not the worker escalation {@link JobLeaseService} used to find it - {@code
 * analysis_job}'s row-level security policy accepts a matching tenant just as well as the
 * escalation flag (V2 migration), and {@code analysis_run}/{@code poison_report} only ever accept
 * the tenant match.
 */
@Service
public class JobOutcomeService {

    private final AnalysisJobRepository jobRepository;
    private final AnalysisRunRepository runRepository;
    private final PoisonReportRepository poisonReportRepository;
    private final RetryPolicy retryPolicy;

    public JobOutcomeService(
            AnalysisJobRepository jobRepository,
            AnalysisRunRepository runRepository,
            PoisonReportRepository poisonReportRepository,
            RetryPolicy retryPolicy) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.poisonReportRepository = poisonReportRepository;
        this.retryPolicy = retryPolicy;
    }

    @Transactional
    public void recordSuccess(UUID jobId, Instant now) {
        AnalysisJob job = requireJob(jobId);
        job.succeed(now);
    }

    /**
     * §4.2's poison-message path: retry with full-jitter backoff while {@link
     * RetryPolicy#shouldRetry} allows it, otherwise quarantine the run and keep a {@link
     * PoisonReport} of why - never silently drop a report that could not be processed.
     */
    @Transactional
    public void recordFailure(UUID jobId, UUID organizationId, UUID analysisRunId, int attemptCount, String error, Instant now) {
        AnalysisJob job = requireJob(jobId);
        AnalysisRun run = runRepository
                .findById(analysisRunId)
                .orElseThrow(() -> new IllegalStateException("Run " + analysisRunId + " for job " + jobId + " is missing"));

        run.markFailed(error, now);
        if (retryPolicy.shouldRetry(attemptCount)) {
            job.retryAt(retryPolicy.nextAttemptAt(now, attemptCount), error, now);
            return;
        }

        job.die(error, now);
        run.markQuarantined(error, now);
        poisonReportRepository.save(
                new PoisonReport(UUID.randomUUID(), organizationId, analysisRunId, attemptCount, error, now));
    }

    private AnalysisJob requireJob(UUID jobId) {
        return jobRepository
                .findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " was claimed but is now missing"));
    }
}
