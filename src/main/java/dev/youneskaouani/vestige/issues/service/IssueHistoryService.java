package dev.youneskaouani.vestige.issues.service;

import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.issues.domain.FindingRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Backs {@code GET /api/v1/issues/{id}/history} - see {@link IssueHistory}. */
@Service
public class IssueHistoryService {

    private final IssueRepository issueRepository;
    private final FindingRepository findingRepository;
    private final TriageEventRepository triageEventRepository;

    public IssueHistoryService(
            IssueRepository issueRepository,
            FindingRepository findingRepository,
            TriageEventRepository triageEventRepository) {
        this.issueRepository = issueRepository;
        this.findingRepository = findingRepository;
        this.triageEventRepository = triageEventRepository;
    }

    @Transactional(readOnly = true)
    public IssueHistory history(UUID issueId) {
        Issue issue =
                issueRepository
                        .findById(issueId)
                        .orElseThrow(() -> new Problems.NotFound("Issue", issueId));
        return new IssueHistory(
                issue,
                findingRepository.findAllByIssueIdOrderBySeq(issueId),
                triageEventRepository.findAllByIssueIdOrderBySequenceNumberAsc(issueId));
    }
}
