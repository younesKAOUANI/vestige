package dev.youneskaouani.vestige.triage.service;

import dev.youneskaouani.vestige.common.hash.HashChain;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/audit/verify} (§6): walks the current tenant's chain from the genesis and
 * recomputes every entry's hash from its own stored payload and {@code prev_hash}, rather than
 * trusting the stored {@code entry_hash} column - the whole point is to notice a row that was
 * edited directly in the database, which by definition bypassed whatever wrote a correct hash next
 * to it.
 *
 * <p>Detects, does not prevent - editing is only actually blocked by the {@code BEFORE UPDATE OR
 * DELETE} trigger from V4. This class is the other half of §6's promise: proving, after the fact,
 * that the trigger was never bypassed either (a superuser, a restored backup, a bug in a future
 * migration).
 */
@Service
public class AuditChainVerifier {

    private final TriageEventRepository eventRepository;

    public AuditChainVerifier(TriageEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** The result named in §6: intact with a length, or broken at the first index that does not chain. */
    public sealed interface VerificationResult {

        record Intact(long length) implements VerificationResult {
        }

        /** @param brokenAtIndex 0-based position, in chain order, of the first entry that does not verify */
        record Broken(long brokenAtIndex) implements VerificationResult {
        }
    }

    @Transactional(readOnly = true)
    public VerificationResult verify() {
        List<TriageEvent> events = eventRepository.findAllByOrderBySequenceNumberAsc();

        String expectedPrevHash = HashChain.GENESIS_HASH;
        for (int index = 0; index < events.size(); index++) {
            TriageEvent event = events.get(index);
            if (!expectedPrevHash.equals(event.getPrevHash())) {
                return new VerificationResult.Broken(index);
            }
            String recomputed = HashChain.entryHash(event.getPrevHash(), event.canonicalPayload());
            if (!recomputed.equals(event.getEntryHash())) {
                return new VerificationResult.Broken(index);
            }
            expectedPrevHash = event.getEntryHash();
        }
        return new VerificationResult.Intact(events.size());
    }
}
