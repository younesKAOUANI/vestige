package dev.youneskaouani.vestige.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A report that exhausted its attempts.
 *
 * <p>Kept rather than dropped. A report that cannot be processed is usually a broken analyser
 * integration, and the failure mode to avoid is the one where CI keeps reporting success while
 * nothing is actually being tracked.
 */
@Entity
@Table(name = "poison_report")
public class PoisonReport {

    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", nullable = false, columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PoisonReport() {
        // for JPA
    }

    public PoisonReport(
            UUID id,
            UUID organizationId,
            UUID analysisRunId,
            int attemptCount,
            String lastError,
            Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.analysisRunId = analysisRunId;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.createdAt = createdAt;
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

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
