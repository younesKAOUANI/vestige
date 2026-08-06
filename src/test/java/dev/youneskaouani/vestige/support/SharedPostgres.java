package dev.youneskaouani.vestige.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container, shared by every {@code @Tag("integration")} test class in a run, started
 * eagerly and never stopped explicitly - Testcontainers' own documented "singleton container"
 * pattern, used precisely for this situation: several unrelated test classes each want a real
 * Postgres, and none of them individually owns the container's lifecycle.
 *
 * <p>Deliberately not a JUnit-managed {@code @Container} field: a {@code static @Container} field
 * declared on a shared abstract base class is started and stopped per <em>subclass's</em> {@code
 * beforeAll}/{@code afterAll}, which for several subclasses means the first one to finish stops the
 * container the others still need. Starting it once, here, in a static initialiser, and letting
 * Testcontainers' Ryuk reaper remove it when the JVM exits, sidesteps that entirely.
 */
public final class SharedPostgres {

    public static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vestige")
            .withUsername("vestige_owner")
            .withPassword("vestige_owner_password");

    static {
        INSTANCE.start();
    }

    private SharedPostgres() {
    }
}
