package dev.youneskaouani.vestige.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The bytes a client uploaded, kept until the run has been processed.
 *
 * <p>Separate from {@link AnalysisRun} so that the run row - which is listed, filtered and joined
 * constantly - is not dragging a megabyte of SARIF around with it. In production this would hold an
 * object-storage key rather than the bytes; keeping them in the database is a deliberate
 * simplification and is called out in the README.
 *
 * <p>No diff column: file-rename resolution is {@code ScmRenameResolver}'s job against the
 * provider's compare API (§3.2), not a client-supplied unified diff, so there is nothing
 * diff-shaped to persist here.
 */
@Entity
@Table(name = "analysis_report_payload")
public class AnalysisReportPayload {

    @Id
    @Column(name = "analysis_run_id")
    private UUID analysisRunId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private byte[] sarif;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AnalysisReportPayload() {
        // for JPA
    }

    public AnalysisReportPayload(
            UUID analysisRunId, UUID organizationId, byte[] sarif, Instant receivedAt) {
        this.analysisRunId = analysisRunId;
        this.organizationId = organizationId;
        this.sarif = sarif.clone();
        this.receivedAt = receivedAt;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public byte[] getSarif() {
        return sarif.clone();
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
