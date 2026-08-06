package dev.youneskaouani.vestige.gate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One persisted row of a {@link QualityGate}'s configuration - not to be confused with
 * {@link GateCondition}, the plain value type {@link dev.youneskaouani.vestige.gate.service.QualityGateEvaluator}
 * actually evaluates. This class is storage; {@link #toGateCondition()} is the bridge between them.
 */
@Entity
@Table(name = "quality_gate_condition")
public class QualityGateCondition {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "quality_gate_id", nullable = false)
    private UUID qualityGateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false)
    private ConditionType conditionType;

    @Column(nullable = false)
    private long threshold;

    /** Display/evaluation order within the gate - stable so a UI list does not reshuffle on every load. */
    @Column(nullable = false)
    private int position;

    protected QualityGateCondition() {
        // for JPA
    }

    public QualityGateCondition(
            UUID id, UUID organizationId, UUID qualityGateId, ConditionType conditionType, long threshold, int position) {
        this.id = id;
        this.organizationId = organizationId;
        this.qualityGateId = qualityGateId;
        this.conditionType = conditionType;
        this.threshold = threshold;
        this.position = position;
    }

    public GateCondition toGateCondition() {
        return new GateCondition(conditionType, threshold);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getQualityGateId() {
        return qualityGateId;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public long getThreshold() {
        return threshold;
    }

    public int getPosition() {
        return position;
    }
}
