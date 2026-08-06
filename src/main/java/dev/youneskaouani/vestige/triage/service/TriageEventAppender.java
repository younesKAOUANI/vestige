package dev.youneskaouani.vestige.triage.service;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.hash.HashChain;
import dev.youneskaouani.vestige.triage.domain.AuditChainHead;
import dev.youneskaouani.vestige.triage.domain.AuditChainHeadRepository;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one entry to an organisation's hash chain (§6). The only writer of {@link TriageEvent}
 * rows and of {@link AuditChainHead#advance} - every other class that wants to record a triage
 * decision goes through {@link #append}, so there is exactly one place that can get the hashing
 * wrong.
 *
 * <p>Must run in the caller's transaction, not its own: the {@link TriageEvent} row and the {@link
 * dev.youneskaouani.vestige.issues.domain.Issue#applyTriage} status change it records have to
 * commit together, or the audit log and the issue it describes could disagree.
 */
@Service
public class TriageEventAppender {

    private final AuditChainHeadRepository headRepository;
    private final TriageEventRepository eventRepository;

    public TriageEventAppender(AuditChainHeadRepository headRepository, TriageEventRepository eventRepository) {
        this.headRepository = headRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * @implNote Uses the caller's ambient transaction. The first event ever appended for an
     *     organisation finds no {@link AuditChainHead} row and creates one; two triage actions
     *     racing on an organisation's very first event both attempt that insert, and the loser sees
     *     a unique-constraint violation on {@code audit_chain_head}'s primary key, mapped to a
     *     generic {@code 409} by {@code GlobalExceptionHandler} - the same accepted, narrow race
     *     documented on {@code RunIngestionService#submit}. Every append after the first always
     *     finds the row and takes {@code FOR UPDATE} on it, which is what serialises the rest.
     */
    @Transactional
    public TriageEvent append(
            UUID organizationId,
            UUID issueId,
            String actor,
            IssueStatus fromStatus,
            IssueStatus toStatus,
            String justification,
            Instant now) {
        AuditChainHead head =
                headRepository.lockForOrganization(organizationId).orElseGet(() -> new AuditChainHead(organizationId, now));

        Map<String, Object> payload =
                TriageEvent.canonicalPayload(issueId, actor, fromStatus, toStatus, justification, now);
        String entryHash = HashChain.entryHash(head.getLastHash(), payload);
        long sequenceNumber = head.getLength() + 1;

        TriageEvent event = new TriageEvent(
                UUID.randomUUID(),
                organizationId,
                issueId,
                sequenceNumber,
                actor,
                fromStatus,
                toStatus,
                justification,
                now,
                head.getLastHash(),
                entryHash);
        eventRepository.save(event);

        head.advance(entryHash, now);
        headRepository.save(head);

        return event;
    }
}
