package dev.youneskaouani.vestige.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for every {@code @Tag("integration")} test that needs a real Postgres: RLS and {@code FOR
 * UPDATE SKIP LOCKED} cannot be exercised against H2 (§9), so these tests are the one place this
 * project asks for a Docker daemon - see README "Testing".
 *
 * <p><b>Two roles, on purpose, exactly like {@code application.yml} configures for local dev.</b>
 * {@code spring.flyway.*} connects as the container's own superuser, because migrations have to
 * {@code CREATE ROLE vestige_app} and enable {@code FORCE ROW LEVEL SECURITY} (V2). {@code
 * spring.datasource.*} - the connection every repository call in these tests actually goes through
 * - connects as {@code vestige_app} instead. That second choice is load-bearing, not a style
 * preference: PostgreSQL never applies row-level security to a superuser, {@code FORCE} or not, so
 * wiring the application datasource to the container's default (super)user - which is exactly what
 * Spring Boot's {@code @ServiceConnection} would do - would make {@code
 * VestigeAdversarialTenancyIT} pass for the wrong reason, or worse, leave it nothing to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    protected static final String APP_PASSWORD = "vestige_app_test_password";

    @DynamicPropertySource
    static void wireContainer(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = SharedPostgres.INSTANCE;

        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appPassword", () -> APP_PASSWORD);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "vestige_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        // A background poll racing a test's own manual transactions is a bug waiting to happen -
        // OutboxSkipLockedConcurrencyIT in particular claims jobs by hand and must be the only claimant.
        registry.add("vestige.worker.poll-interval", () -> "24h");
    }
}
