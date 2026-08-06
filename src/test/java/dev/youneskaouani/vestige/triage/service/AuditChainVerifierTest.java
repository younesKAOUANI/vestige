package dev.youneskaouani.vestige.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.hash.HashChain;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises the recomputation logic directly, independent of whether the database trigger from V4
 * actually stopped a mutation - {@code VestigeAudit*IT} covers that half against a real Postgres.
 * This class answers a narrower question: given rows exactly as a bypassed trigger (a restored
 * backup, a superuser) would leave them, does {@link AuditChainVerifier} notice?
 */
@ExtendWith(MockitoExtension.class)
class AuditChainVerifierTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TriageEventRepository eventRepository;

    private AuditChainVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AuditChainVerifier(eventRepository);
    }

    @Test
    @DisplayName("an empty chain is intact with length zero")
    void anEmptyChainIsIntact() {
        when(eventRepository.findAllByOrderBySequenceNumberAsc()).thenReturn(List.of());

        AuditChainVerifier.VerificationResult result = verifier.verify();

        assertThat(result).isEqualTo(new AuditChainVerifier.VerificationResult.Intact(0));
    }

    @Test
    @DisplayName("a properly chained sequence of events verifies intact")
    void aProperlyChainedSequenceVerifies() {
        when(eventRepository.findAllByOrderBySequenceNumberAsc()).thenReturn(chainOf(5));

        AuditChainVerifier.VerificationResult result = verifier.verify();

        assertThat(result).isEqualTo(new AuditChainVerifier.VerificationResult.Intact(5));
    }

    @Test
    @DisplayName("a payload edited after the fact - same stored hash, different content - is caught")
    void detectsATamperedPayload() {
        List<TriageEvent> events = new ArrayList<>(chainOf(3));
        events.set(1, withJustification(events.get(1), "not what was actually recorded"));
        when(eventRepository.findAllByOrderBySequenceNumberAsc()).thenReturn(events);

        AuditChainVerifier.VerificationResult result = verifier.verify();

        assertThat(result).isEqualTo(new AuditChainVerifier.VerificationResult.Broken(1));
    }

    @Test
    @DisplayName("a row spliced out from the middle breaks the link into the next one")
    void detectsAMissingLink() {
        List<TriageEvent> events = new ArrayList<>(chainOf(3));
        events.remove(1); // sequence numbers 1 and 3 remain; 3's prev_hash no longer matches 1's entry_hash
        when(eventRepository.findAllByOrderBySequenceNumberAsc()).thenReturn(events);

        AuditChainVerifier.VerificationResult result = verifier.verify();

        assertThat(result).isEqualTo(new AuditChainVerifier.VerificationResult.Broken(1));
    }

    /** A correctly-hashed chain of {@code count} events, built the same way {@code TriageEventAppender} would. */
    private static List<TriageEvent> chainOf(int count) {
        List<TriageEvent> events = new ArrayList<>();
        String prevHash = HashChain.GENESIS_HASH;
        for (int sequenceNumber = 1; sequenceNumber <= count; sequenceNumber++) {
            UUID issueId = UUID.randomUUID();
            Instant occurredAt = NOW.plusSeconds(sequenceNumber);
            String justification = "reason " + sequenceNumber;
            Map<String, Object> payload = TriageEvent.canonicalPayload(
                    issueId, "younes", IssueStatus.OPEN, IssueStatus.RESOLVED_WONT_FIX, justification, occurredAt);
            String entryHash = HashChain.entryHash(prevHash, payload);

            events.add(new TriageEvent(
                    UUID.randomUUID(),
                    ORG_ID,
                    issueId,
                    sequenceNumber,
                    "younes",
                    IssueStatus.OPEN,
                    IssueStatus.RESOLVED_WONT_FIX,
                    justification,
                    occurredAt,
                    prevHash,
                    entryHash));
            prevHash = entryHash;
        }
        return events;
    }

    private static TriageEvent withJustification(TriageEvent original, String tamperedJustification) {
        // Same id, sequence number, prev_hash and (crucially) entry_hash as the original - exactly
        // what a raw UPDATE bypassing V4's trigger would leave: the stored hash no longer matches the
        // payload it is supposed to attest to.
        return new TriageEvent(
                original.getId(),
                original.getOrganizationId(),
                original.getIssueId(),
                original.getSequenceNumber(),
                original.getActor(),
                original.getFromStatus(),
                original.getToStatus(),
                tamperedJustification,
                original.getOccurredAt(),
                original.getPrevHash(),
                original.getEntryHash());
    }
}
