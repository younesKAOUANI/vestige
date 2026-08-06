package dev.youneskaouani.vestige.triage.domain;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One entry in the append-only, hash-chained triage log (§6): who changed an issue's status, when,
 * from what, to what, and why. Enforced immutable twice over - once here, by never exposing a
 * setter, and once in the database, by the {@code BEFORE UPDATE OR DELETE} trigger V4 adds, which
 * is the enforcement that actually matters (see V4__triage_event_append_only.sql: an attacker with
 * a SQL client bypasses this class entirely, but not the trigger).
 *
 * <p><b>{@code actor}, not {@code actor_id}.</b> §6's payload names {@code actor_id}, which implies
 * a foreign key into a users table - but v1 has no per-user identity at all (§11 excludes SSO/SCIM
 * on purpose), only organisation-scoped API keys. {@code actor} is therefore a caller-supplied,
 * unverified free-text string (see {@code TriageController}), not a checked reference. That is a
 * real, documented gap - the audit chain proves an action happened and was not later altered, not
 * that the named actor is who actually performed it - and README "Roadmap" says so plainly rather
 * than let the field name imply a guarantee this system does not make.
 */
@Entity
@Table(name = "triage_event")
public class TriageEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(nullable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false)
    private IssueStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private IssueStatus toStatus;

    @Column
    private String justification;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "prev_hash", nullable = false)
    private String prevHash;

    @Column(name = "entry_hash", nullable = false)
    private String entryHash;

    protected TriageEvent() {
        // for JPA
    }

    public TriageEvent(
            UUID id,
            UUID organizationId,
            UUID issueId,
            long sequenceNumber,
            String actor,
            IssueStatus fromStatus,
            IssueStatus toStatus,
            String justification,
            Instant occurredAt,
            String prevHash,
            String entryHash) {
        this.id = id;
        this.organizationId = organizationId;
        this.issueId = issueId;
        this.sequenceNumber = sequenceNumber;
        this.actor = actor;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.justification = justification;
        this.occurredAt = occurredAt;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
    }

    /**
     * The exact payload {@code entry_hash} is computed over (§6). A {@code Map}, not this entity
     * itself, so {@code TriageEventAppender} (building a not-yet-persisted event) and {@code
     * AuditChainVerifier} (re-hashing an already-persisted row) are guaranteed to feed
     * {@link dev.youneskaouani.vestige.common.hash.HashChain} the identical shape - six fields,
     * matching §6's {@code {issue_id, actor_id, from_status, to_status, justification,
     * occurred_at}} one for one (renaming {@code actor_id} to {@code actor} - see the class
     * javadoc).
     */
    public static Map<String, Object> canonicalPayload(
            UUID issueId, String actor, IssueStatus fromStatus, IssueStatus toStatus, String justification,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issueId", issueId.toString());
        payload.put("actor", actor);
        payload.put("fromStatus", fromStatus.name());
        payload.put("toStatus", toStatus.name());
        payload.put("justification", justification);
        payload.put("occurredAt", occurredAt.toString());
        return payload;
    }

    public Map<String, Object> canonicalPayload() {
        return canonicalPayload(issueId, actor, fromStatus, toStatus, justification, occurredAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getActor() {
        return actor;
    }

    public IssueStatus getFromStatus() {
        return fromStatus;
    }

    public IssueStatus getToStatus() {
        return toStatus;
    }

    public String getJustification() {
        return justification;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }
}
