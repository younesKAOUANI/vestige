package dev.youneskaouani.vestige.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the reports in {@code sarif-fixtures/}, which the build puts on the test classpath.
 *
 * <p>The tests and {@code scripts/demo.sh} deliberately read the same files: a fixture that only
 * the tests use will drift away from the demo, and a demo nobody tests will break silently.
 */
final class SarifFixtures {

    private static final String ROOT = "sarif-fixtures/";

    private SarifFixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream stream =
                SarifFixtures.class.getClassLoader().getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not on the classpath: " + ROOT + name);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read fixture " + name, e);
        }
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}
