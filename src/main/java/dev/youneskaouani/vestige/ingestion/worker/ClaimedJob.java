package dev.youneskaouani.vestige.ingestion.worker;

import java.util.UUID;

/**
 * A job {@link JobLeaseService} has just claimed and leased, reduced to the scalars {@link
 * OutboxWorker} needs - not the {@code AnalysisJob} entity itself, which would be detached the
 * moment the claiming transaction commits and is one field away from confusing "detached" with
 * "safe to read forever".
 */
public record ClaimedJob(UUID jobId, UUID organizationId, UUID analysisRunId, int attemptCount) {}
