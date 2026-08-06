package dev.youneskaouani.vestige.common.hash;

import java.util.Map;

/**
 * The hash-chaining primitive behind the tamper-evident triage log (§6).
 *
 * <p>{@code entry_hash = SHA-256(prev_hash || SHA-256(canonical_json(payload)))}, exactly as §6
 * states it. Because {@code prev_hash} is folded into the pre-image, editing any historical row
 * invalidates that row and every row after it; the verifier can therefore name the exact link that
 * broke.
 *
 * <p>The outer concatenation is plain string {@code +}, not {@link Sha256#hexOfFields}'s
 * length-prefixed field separation: {@code hexOfFields} exists to keep two <em>variable-length</em>
 * strings from colliding at their boundary (see its own javadoc), but both operands here are
 * always exactly 64 lowercase hex characters - a SHA-256 digest, either {@link #GENESIS_HASH} or a
 * previous {@code entry_hash} - so there is no boundary for a collision to hide in.
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
        String payloadHash = Sha256.hex(CanonicalJson.write(payload));
        return Sha256.hex(previous + payloadHash);
    }
}
