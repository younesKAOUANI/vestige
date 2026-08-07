package dev.youneskaouani.vestige.ingestion.worker;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link RetryPolicy} the outbox worker uses from {@code vestige.worker.*}, rather than
 * {@link RetryPolicy#defaults()} - the numbers happen to be the same as §4.2's (see
 * application.yml), but the point of exposing them as configuration at all is that an operator can
 * change them without a rebuild.
 */
@Configuration(proxyBeanMethods = false)
public class WorkerConfig {

    @Bean
    public RetryPolicy retryPolicy(VestigeProperties properties) {
        VestigeProperties.Worker worker = properties.worker();
        return RetryPolicy.of(
                worker.retryBaseDelay(), worker.retryMaxDelay(), worker.maxAttempts());
    }
}
