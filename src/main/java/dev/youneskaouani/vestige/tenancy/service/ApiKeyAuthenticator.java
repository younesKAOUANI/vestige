package dev.youneskaouani.vestige.tenancy.service;

import dev.youneskaouani.vestige.common.hash.Sha256;
import dev.youneskaouani.vestige.common.util.ConstantTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an API key to the organization that owns it.
 *
 * <p>Keys look like {@code vst_<prefix>_<secret>}. The prefix is a non-secret handle used to find
 * the row; the secret is compared against a SHA-256 of the whole key, in constant time.
 *
 * <p>A slow KDF is deliberately not used. Argon2 and bcrypt exist to make a <em>guessable</em>
 * secret expensive to attack; these keys are 256 bits from a CSPRNG, so there is nothing to guess
 * and the only thing a KDF would add is latency on every single request. The property that matters
 * here - a database dump does not yield usable credentials - is already given by the hash.
 *
 * <p>The lookup goes through the {@code vestige_lookup_api_key} SECURITY DEFINER function, because
 * {@code api_key} is protected by a row-level security policy that cannot be satisfied before the
 * tenant is known. See {@code V3__api_key_authentication.sql}.
 */
@Service
public class ApiKeyAuthenticator {

    private static final String LOOKUP =
            "select api_key_id, organization_id, key_hash from vestige_lookup_api_key(:prefix)";
    private static final String TOUCH = "select vestige_touch_api_key(:id)";

    /** Everything before this many characters of the secret segment is the lookup handle. */
    public static final String KEY_NAMESPACE = "vst";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param presentedKey the raw value of the {@code X-API-Key} header
     * @return the organization the key belongs to, or empty if the key is unknown or wrong
     */
    @Transactional
    public Optional<UUID> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        Optional<String> prefix = prefixOf(presentedKey);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }

        Query lookup = entityManager.createNativeQuery(LOOKUP).setParameter("prefix", prefix.get());
        List<?> rows = lookup.getResultList();
        if (rows.size() != 1) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        UUID apiKeyId = (UUID) row[0];
        UUID organizationId = (UUID) row[1];
        String storedHash = (String) row[2];

        if (!ConstantTime.equals(storedHash, hash(presentedKey))) {
            return Optional.empty();
        }
        entityManager.createNativeQuery(TOUCH).setParameter("id", apiKeyId).getSingleResult();
        return Optional.of(organizationId);
    }

    /** The hash stored in {@code api_key.key_hash}. */
    public static String hash(String rawKey) {
        return Sha256.hex(rawKey);
    }

    /**
     * The lookup handle of a key: {@code vst_<prefix>_<secret>} yields {@code <prefix>}.
     *
     * <p>Returning empty for a malformed key means a bad header costs one string split rather than
     * a database round trip.
     */
    public static Optional<String> prefixOf(String rawKey) {
        String[] parts = rawKey.trim().split("_");
        if (parts.length != 3 || !KEY_NAMESPACE.equals(parts[0]) || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts[1]);
    }
}
