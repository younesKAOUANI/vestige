package dev.youneskaouani.vestige.issues.service;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.issues.domain.IssueSpecifications;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET /api/v1/projects/{id}/issues} (§8): composes {@link IssueSpecifications} from
 * the request's filters, resolving {@code since-run} to an instant here so that class can stay a
 * plain, repository-free set of predicate factories - see its own javadoc.
 */
@Service
public class IssueQueryService {

    private final IssueRepository issueRepository;
    private final AnalysisRunRepository runRepository;

    public IssueQueryService(IssueRepository issueRepository, AnalysisRunRepository runRepository) {
        this.issueRepository = issueRepository;
        this.runRepository = runRepository;
    }

    @Transactional(readOnly = true)
    public Page<Issue> search(
            UUID projectId, IssueStatus status, Severity severity, String ruleId, UUID sinceRunId, Pageable pageable) {
        Instant since = sinceRunId == null ? null : sinceRunCreatedAt(sinceRunId);

        // Specification.where(...).and(...) tolerates a null operand at every step (each factory
        // above returns null for "no filter") - see IssueSpecifications' own javadoc, which this
        // relies on rather than a Specification.allOf(...) helper whose presence varies by version.
        Specification<Issue> spec = Specification.where(IssueSpecifications.projectId(projectId))
                .and(IssueSpecifications.status(status))
                .and(IssueSpecifications.severity(severity))
                .and(IssueSpecifications.ruleId(ruleId))
                .and(IssueSpecifications.updatedSince(since));

        return issueRepository.findAll(spec, pageable);
    }

    private Instant sinceRunCreatedAt(UUID sinceRunId) {
        return runRepository
                .findById(sinceRunId)
                .map(AnalysisRun::getCreatedAt)
                .orElseThrow(() -> new Problems.NotFound("Run", sinceRunId));
    }
}
