package dev.youneskaouani.vestige.triage.domain;

import dev.youneskaouani.vestige.common.hash.HashChain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The current tail of one organisation's triage hash chain (§6).
 *
 * <p>This row - one per organisation - is what {@code TriageEventAppender} takes with {@code
 * SELECT ... FOR UPDATE} before computing a new entry's hash. That lock is what makes concurrent
 * triage within one organisation safe without a distributed lock: two transactions racing to
 * append both need this exact row, so the second one waits, sees the first one's committed
 * {@code lastHash}, and chains onto <em>that</em> - never onto a stale value. Concurrent triage in
 * two <em>different</em> organisations never contends, since they lock different rows.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false)
    private long length;

    @Column(name = "last_hash", nullable = false)
    private String lastHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuditChainHead() {
        // for JPA
    }

    /** The empty chain: length 0, tail is the genesis constant. */
    public AuditChainHead(UUID organizationId, Instant now) {
        this.organizationId = organizationId;
        this.length = 0;
        this.lastHash = HashChain.GENESIS_HASH;
        this.updatedAt = now;
    }

    /** Advances the chain by one entry. Called only while this row is held under {@code FOR UPDATE}. */
    public void advance(String newEntryHash, Instant now) {
        this.length++;
        this.lastHash = newEntryHash;
        this.updatedAt = now;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public long getLength() {
        return length;
    }

    public String getLastHash() {
        return lastHash;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
