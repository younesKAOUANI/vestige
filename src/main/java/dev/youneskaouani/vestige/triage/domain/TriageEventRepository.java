package dev.youneskaouani.vestige.triage.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriageEventRepository extends JpaRepository<TriageEvent, UUID> {

    /**
     * One issue's full triage timeline, oldest first - part of {@code GET
     * /api/v1/issues/{id}/history} (§8).
     */
    List<TriageEvent> findAllByIssueIdOrderBySequenceNumberAsc(UUID issueId);

    /**
     * The whole chain, oldest first - what {@code GET /api/v1/audit/verify} (§6) walks. No explicit
     * {@code organizationId} parameter: row-level security already restricts this to the current
     * tenant (see {@code ProjectRepository} for the same convention).
     */
    List<TriageEvent> findAllByOrderBySequenceNumberAsc();
}
