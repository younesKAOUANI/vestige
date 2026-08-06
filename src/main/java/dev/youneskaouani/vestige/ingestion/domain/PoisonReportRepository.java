package dev.youneskaouani.vestige.ingestion.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoisonReportRepository extends JpaRepository<PoisonReport, UUID> {

    List<PoisonReport> findAllByAnalysisRunId(UUID analysisRunId);
}
