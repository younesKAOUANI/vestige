package dev.youneskaouani.vestige.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A unit of work in the database-backed queue.
 *
 * <p>One row per run, created in the same transaction as the run itself. That is the whole reason
 * for choosing a table over a broker here: enqueueing and accepting the submission either both
 * happen or neither does, with no outbox and no window in which a client has been told 202 for work
 * that was never queued.
 */
@Entity
@Table(name = "analysis_job")
public class AnalysisJob {

    /** State of a queued job. */
    public enum JobStatus {
        PENDING,
        RUNNING,
        DONE,
        DEAD
    }

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AnalysisJob() {
        // for JPA
    }

    public AnalysisJob(UUID id, UUID organizationId, UUID analysisRunId, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.analysisRunId = analysisRunId;
        this.status = JobStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Claims the job for one worker for {@code leaseDuration}. */
    public void lease(Instant now, Duration leaseDuration) {
        this.status = JobStatus.RUNNING;
        this.attemptCount++;
        this.lockedUntil = now.plus(leaseDuration);
        this.updatedAt = now;
    }

    public void succeed(Instant now) {
        this.status = JobStatus.DONE;
        this.lockedUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /** Schedules a retry at {@code retryAt}, remembering why. */
    public void retryAt(Instant retryAt, String error, Instant now) {
        this.status = JobStatus.PENDING;
        this.nextAttemptAt = retryAt;
        this.lockedUntil = null;
        this.lastError = error;
        this.updatedAt = now;
    }

    /** Gives up: the run is dead and a poison record is written alongside. */
    public void die(String error, Instant now) {
        this.status = JobStatus.DEAD;
        this.lockedUntil = null;
        this.lastError = error;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
