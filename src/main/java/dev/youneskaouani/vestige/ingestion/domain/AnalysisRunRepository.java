package dev.youneskaouani.vestige.ingestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /**
     * §4.1's natural key - {@code sha256(project ‖ commit ‖ analyser ‖ report_digest)}'s inputs
     * before hashing, not after: a second lookup {@code RunIngestionService} falls back to when the
     * caller's {@code Idempotency-Key} does not match anything, in case that key differs from a
     * previous submission's (or was omitted this time and supplied then) but the underlying report
     * is identical regardless.
     */
    Optional<AnalysisRun> findByProjectIdAndCommitShaAndAnalyserNameAndReportDigest(
            UUID projectId, String commitSha, String analyserName, String reportDigest);

    Optional<AnalysisRun> findByOrganizationIdAndIdempotencyKey(
            UUID organizationId, String idempotencyKey);
}
