package dev.youneskaouani.vestige.triage.api;

import dev.youneskaouani.vestige.triage.service.AuditChainVerifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/audit/verify} (§6, §8). */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditChainVerifier verifier;

    public AuditController(AuditChainVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/verify")
    public AuditVerifyResponse verify() {
        return AuditVerifyResponse.of(verifier.verify());
    }
}
