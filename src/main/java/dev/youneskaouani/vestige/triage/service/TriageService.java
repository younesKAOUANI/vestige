package dev.youneskaouani.vestige.triage.service;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PATCH /api/v1/issues/{id}} (§8): a human triage decision, always paired with a {@link
 * dev.youneskaouani.vestige.triage.domain.TriageEvent} - never one without the other, since an
 * unaudited status change on an {@link Issue} is exactly what §6 exists to make impossible.
 */
@Service
public class TriageService {

    private final IssueRepository issueRepository;
    private final TriageEventAppender appender;

    public TriageService(IssueRepository issueRepository, TriageEventAppender appender) {
        this.issueRepository = issueRepository;
        this.appender = appender;
    }

    /**
     * @param actor caller-supplied, unverified - see {@code TriageEvent}'s class javadoc for why
     * @throws Problems.BadRequest {@code newStatus} is one of the two statuses §8 requires a
     *     justification for ({@link IssueStatus#requiresTriage()}) and none was given
     * @throws Problems.Conflict the issue is already in {@code newStatus} - triage records a
     *     transition, and there is no transition to record here
     */
    @Transactional
    public Issue applyTriage(
            UUID organizationId,
            UUID issueId,
            IssueStatus newStatus,
            String actor,
            String justification,
            Instant now) {
        Issue issue =
                issueRepository
                        .findById(issueId)
                        .orElseThrow(() -> new Problems.NotFound("Issue", issueId));

        if (newStatus.requiresTriage() && (justification == null || justification.isBlank())) {
            throw new Problems.BadRequest(
                    "A justification is required to set an issue's status to " + newStatus);
        }
        IssueStatus previousStatus = issue.getStatus();
        if (previousStatus == newStatus) {
            throw new Problems.Conflict("Issue " + issueId + " already has status " + newStatus);
        }

        // Deliberately permissive about which transitions PATCH may request - see
        // Issue#applyTriage's
        // own javadoc: this is also how a mistaken triage gets corrected back to OPEN, and §8
        // states
        // only one hard rule (the justification above), not a transition table.
        issue.applyTriage(newStatus, now);
        appender.append(
                organizationId, issueId, actor, previousStatus, newStatus, justification, now);

        return issue;
    }
}
