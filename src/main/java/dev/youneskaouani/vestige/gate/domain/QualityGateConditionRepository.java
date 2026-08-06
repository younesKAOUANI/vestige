package dev.youneskaouani.vestige.gate.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityGateConditionRepository extends JpaRepository<QualityGateCondition, UUID> {

    List<QualityGateCondition> findAllByQualityGateIdOrderByPosition(UUID qualityGateId);

    /** {@code PUT /api/v1/projects/{id}/gate} replaces a gate's conditions wholesale - see GateConfigService. */
    void deleteAllByQualityGateId(UUID qualityGateId);
}
