package dev.youneskaouani.vestige.common.hash;

import java.util.Map;

/**
 * The hash-chaining primitive behind the tamper-evident triage log.
 *
 * <p>{@code entry_hash = SHA-256(prev_hash || canonical_json(payload))}. Because {@code prev_hash}
 * is folded into the pre-image, editing any historical row invalidates that row and every row after
 * it; the verifier can therefore name the exact link that broke.
 */
public final class HashChain {

    /**
     * Pre-image of the first entry of a chain. It is a constant rather than an empty string so that
     * "no previous entry" is distinguishable from "previous entry whose hash happened to be blank".
     */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private HashChain() {
    }

    /** Computes the entry hash for a payload following {@code prevHash}. */
    public static String entryHash(String prevHash, Map<String, Object> payload) {
        String previous = (prevHash == null || prevHash.isBlank()) ? GENESIS_HASH : prevHash;
        return Sha256.hexOfFields(previous, CanonicalJson.write(payload));
    }
}
