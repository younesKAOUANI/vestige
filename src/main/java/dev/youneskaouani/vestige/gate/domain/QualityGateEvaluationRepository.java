package dev.youneskaouani.vestige.gate.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityGateEvaluationRepository
        extends JpaRepository<QualityGateEvaluation, UUID> {

    Optional<QualityGateEvaluation> findByAnalysisRunId(UUID analysisRunId);

    List<QualityGateEvaluation> findAllByProjectIdOrderByEvaluatedAtDesc(
            UUID projectId, Limit limit);
}
