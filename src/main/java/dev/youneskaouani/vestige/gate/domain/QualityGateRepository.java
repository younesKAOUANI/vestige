package dev.youneskaouani.vestige.gate.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityGateRepository extends JpaRepository<QualityGate, UUID> {

    Optional<QualityGate> findByProjectId(UUID projectId);
}
