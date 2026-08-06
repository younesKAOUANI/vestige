package dev.youneskaouani.vestige.triage.api;

import dev.youneskaouani.vestige.triage.service.AuditChainVerifier;

/**
 * {@code GET /api/v1/audit/verify}'s body (§6): {@code {"intact": true, "length": n}} for a clean
 * chain, exactly as specified; a broken chain instead names the first index that failed to verify,
 * leaving {@code length} absent rather than guess at a number that no longer means anything once
 * the chain has a break in it.
 */
public record AuditVerifyResponse(boolean intact, Long length, Long brokenAtIndex) {

    public static AuditVerifyResponse of(AuditChainVerifier.VerificationResult result) {
        return switch (result) {
            case AuditChainVerifier.VerificationResult.Intact intact ->
                    new AuditVerifyResponse(true, intact.length(), null);
            case AuditChainVerifier.VerificationResult.Broken broken ->
                    new AuditVerifyResponse(false, null, broken.brokenAtIndex());
        };
    }
}
