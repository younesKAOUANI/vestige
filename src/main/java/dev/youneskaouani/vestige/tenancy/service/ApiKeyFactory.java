package dev.youneskaouani.vestige.tenancy.service;

import dev.youneskaouani.vestige.tenancy.domain.ApiKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Mints new API keys. */
public final class ApiKeyFactory {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 6 bytes is enough for a collision-free lookup handle and short enough to read in a log. */
    private static final int PREFIX_BYTES = 6;

    /** 32 bytes of CSPRNG output; this is the part that must never be stored. */
    private static final int SECRET_BYTES = 32;

    private ApiKeyFactory() {}

    /**
     * A newly minted key and the row that records it.
     *
     * @param plaintext shown to the user exactly once; never persisted
     */
    public record NewApiKey(ApiKey record, String plaintext) {}

    public static NewApiKey create(UUID organizationId, String name, Instant now) {
        String prefix = randomSegment(PREFIX_BYTES);
        String secret = randomSegment(SECRET_BYTES);
        String plaintext = ApiKeyAuthenticator.KEY_NAMESPACE + "_" + prefix + "_" + secret;
        ApiKey record =
                new ApiKey(
                        UUID.randomUUID(),
                        organizationId,
                        name,
                        prefix,
                        ApiKeyAuthenticator.hash(plaintext),
                        now);
        return new NewApiKey(record, plaintext);
    }

    private static String randomSegment(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        // The URL-safe alphabet excludes '_', which is what separates the segments of a key.
        return ENCODER.encodeToString(buffer).replace('-', 'x').replace('_', 'y');
    }
}
