package dev.youneskaouani.vestige.issues.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findAllByAnalysisRunIdOrderBySeq(UUID analysisRunId);

    /**
     * The finding that represents an issue's most recent sighting - {@code issue.last_seen_run_id}
     * joined back to the finding it came from - which is what {@code IssueTrackingService} builds
     * each {@code PreviousIssueCandidate} out of.
     */
    Optional<Finding> findByIssueIdAndAnalysisRunId(UUID issueId, UUID analysisRunId);

    /** Full finding history of one issue, oldest first - {@code GET /api/v1/issues/{id}/history} (§8). */
    List<Finding> findAllByIssueIdOrderBySeq(UUID issueId);
}
