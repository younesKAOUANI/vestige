package dev.youneskaouani.vestige.ingestion.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /** The natural key that makes submission idempotent. */
    Optional<AnalysisRun> findByProjectIdAndCommitShaAndAnalyserNameAndReportDigest(
            UUID projectId, String commitSha, String analyserName, String reportDigest);

    Optional<AnalysisRun> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    /**
     * The run whose issue state a new run should be matched against: the most recent completed run
     * on the same branch.
     */
    List<AnalysisRun> findByBranchIdAndStatusOrderByCreatedAtDesc(UUID branchId, RunStatus status, Limit limit);
}
