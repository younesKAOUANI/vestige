package dev.youneskaouani.vestige.github.api;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.github.service.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/webhooks/github} (§8): push and PR events, HMAC-SHA256 verified.
 *
 * <p>Bypasses {@code ApiKeyAuthenticationFilter} by path (see its {@code UNAUTHENTICATED_PREFIXES})
 * because GitHub cannot present an {@code X-API-Key}; the signature check below is this endpoint's
 * authentication, not an addition on top of one.
 *
 * <p>Verification is real and tested ({@link WebhookSignatureVerifier}). Acting on the verified
 * payload - re-triggering a re-baseline when a PR's base branch changes, decorating a PR the moment
 * its check run is known rather than waiting for the next poll - is not: v1 verifies and logs every
 * event and stops there. See README "Roadmap"; nothing about the ingestion or matching pipeline
 * depends on this endpoint doing more, since {@code POST /api/v1/runs} is what CI actually calls to
 * submit a report.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String EVENT_HEADER = "X-GitHub-Event";

    private final VestigeProperties properties;

    public GitHubWebhookController(VestigeProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/github")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = EVENT_HEADER, required = false) String event,
            @RequestBody byte[] payload) {

        String secret = properties.github().webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new Problems.Forbidden(
                    "GitHub webhook integration is not configured on this instance");
        }
        if (!new WebhookSignatureVerifier(secret).isValid(payload, signature)) {
            throw new Problems.Unauthorized("Invalid or missing " + SIGNATURE_HEADER);
        }

        // TODO(Roadmap): act on push/pull_request events (re-baseline, PR decoration) instead of
        // only
        // verifying and logging them - see this class's javadoc.
        log.info("Verified GitHub webhook event '{}' ({} bytes)", event, payload.length);
        return ResponseEntity.accepted().build();
    }
}
