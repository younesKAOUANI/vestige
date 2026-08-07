package dev.youneskaouani.vestige.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares {@link SarifReader} as a bean.
 *
 * <p>It is not annotated {@code @Component} itself, and deliberately so: {@code ingestion.sarif} is
 * one of the packages that stays free of any Spring import, both because the streaming reader is
 * worth unit-testing without a container and because {@code scripts/offline-verify.sh} compiles
 * that package against the JDK and Jackson alone. Declaring the bean from outside keeps that
 * boundary intact - the same arrangement {@code WorkerConfig} uses for {@code RetryPolicy} and
 * {@code GitHubConfig} for {@code ScmRenameResolver}.
 *
 * <p>The injected {@link ObjectMapper} is Boot's own auto-configured one, so the reader honours the
 * application's Jackson settings rather than a second, silently-diverging instance.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionConfig {

    @Bean
    public SarifReader sarifReader(ObjectMapper objectMapper) {
        return new SarifReader(objectMapper);
    }
}
