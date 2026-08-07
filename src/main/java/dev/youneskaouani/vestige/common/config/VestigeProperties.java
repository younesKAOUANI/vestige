package dev.youneskaouani.vestige.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Everything under {@code vestige.*} in application.yml, in one typed place rather than scattered
 * {@code @Value} injections. Picked up automatically by {@code @ConfigurationPropertiesScan} on
 * {@link dev.youneskaouani.vestige.VestigeApplication}.
 */
@ConfigurationProperties("vestige")
public record VestigeProperties(
        Ingestion ingestion, Worker worker, Matching matching, GitHub github) {

    /**
     * @param maxReportBytes §4.3: reports above this are rejected with 413 before parsing
     * @param findingBatchSize §4.3: how many findings accumulate before a batch INSERT flushes
     */
    public record Ingestion(DataSize maxReportBytes, int findingBatchSize) {}

    /**
     * @param pollInterval how often an idle worker checks the outbox for runnable jobs
     * @param leaseDuration how long a claimed job is presumed alive before another worker may
     *     reclaim it
     * @param maxAttempts attempts after which a job is quarantined (§4.2)
     * @param retryBaseDelay the {@code 250ms} in §4.2's {@code min(2^n * 250ms, 5min) * U(0,1)}
     * @param retryMaxDelay the {@code 5min} cap in the same formula
     */
    public record Worker(
            Duration pollInterval,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay) {}

    /**
     * @param weakFingerprintLineProximity the "&le; 25" in §3.2's rung-3 fingerprint
     */
    public record Matching(int weakFingerprintLineProximity) {}

    /**
     * @param token optional GitHub token used for the compare API (renames, §3.2) and Check Runs
     *     (§7); blank means those calls are made unauthenticated (public repos only, low rate
     *     limit) or skipped where authentication is mandatory
     * @param webhookSecret HMAC secret for {@code POST /api/v1/webhooks/github}; blank means the
     *     webhook endpoint refuses every request rather than accepting unverified ones
     */
    public record GitHub(String token, String webhookSecret) {}
}
