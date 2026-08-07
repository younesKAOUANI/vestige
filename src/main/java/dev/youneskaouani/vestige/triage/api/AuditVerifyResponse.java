package dev.youneskaouani.vestige.triage.api;

import dev.youneskaouani.vestige.triage.service.AuditChainVerifier;

/**
 * {@code GET /api/v1/audit/verify}'s body (§6): {@code {"intact": true, "length": n}} for a clean
 * chain, exactly as specified; a broken chain instead names the first index that failed to verify,
 * leaving {@code length} absent rather than guess at a number that no longer means anything once
 * the chain has a break in it.
 */
public record AuditVerifyResponse(boolean intact, Long length, Long brokenAtIndex) {

    // VerificationResult is sealed to exactly these two records, so the trailing throw is
    // unreachable in practice. It is spelled out because the language level is 17 and a pattern
    // switch - which would let the compiler prove the exhaustiveness for us - needs 21.
    public static AuditVerifyResponse of(AuditChainVerifier.VerificationResult result) {
        if (result instanceof AuditChainVerifier.VerificationResult.Intact intact) {
            return new AuditVerifyResponse(true, intact.length(), null);
        }
        if (result instanceof AuditChainVerifier.VerificationResult.Broken broken) {
            return new AuditVerifyResponse(false, null, broken.brokenAtIndex());
        }
        throw new IllegalStateException("Unknown VerificationResult: " + result);
    }
}
