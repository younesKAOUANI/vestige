package dev.youneskaouani.vestige.gate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The stored result of evaluating a gate against one run (§7) - one row per run
 * ({@code quality_gate_evaluation_once_per_run}), never revised after it is written.
 *
 * <p>{@code resultJson} is the full {@code GateOutcome} (gate name, verdict, and every condition's
 * actual value, threshold and offending issue ids), serialised once by the caller and stored as
 * {@code jsonb} via Hibernate 6's {@code @JdbcTypeCode(SqlTypes.JSON)} - see §9's own reasoning for
 * jsonb: this is a computed, write-once document nothing needs to query <em>into</em> from SQL,
 * only ever read back whole. {@code status} is duplicated onto its own column because that one
 * field <em>is</em> queried and filtered on directly (gate history, dashboards).
 */
@Entity
@Table(name = "quality_gate_evaluation")
public class QualityGateEvaluation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "gate_name", nullable = false)
    private String gateName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GateStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false)
    private String resultJson;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    protected QualityGateEvaluation() {
        // for JPA
    }

    public QualityGateEvaluation(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID analysisRunId,
            String gateName,
            GateStatus status,
            String resultJson,
            Instant evaluatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.analysisRunId = analysisRunId;
        this.gateName = gateName;
        this.status = status;
        this.resultJson = resultJson;
        this.evaluatedAt = evaluatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public String getGateName() {
        return gateName;
    }

    public GateStatus getStatus() {
        return status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
