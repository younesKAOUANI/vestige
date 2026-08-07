package dev.youneskaouani.vestige.triage.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE} on one organisation's chain head - a dedicated, explicitly
     * locking method rather than adding {@code @Lock} to {@code findById}, so that a read that does
     * not need the lock (e.g. {@code GET /api/v1/audit/verify}) never silently takes one. See
     * {@link AuditChainHead}'s class javadoc for why this lock is what serialises concurrent triage
     * safely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.organizationId = :organizationId")
    Optional<AuditChainHead> lockForOrganization(@Param("organizationId") UUID organizationId);
}
