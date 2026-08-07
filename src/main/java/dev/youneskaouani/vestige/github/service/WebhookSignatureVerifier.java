package dev.youneskaouani.vestige.github.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header.
 *
 * <p>Three details matter and all three are routinely got wrong:
 *
 * <ul>
 *   <li>the MAC is computed over the <em>raw</em> request body, so the controller must take {@code
 *       byte[]} and never a parsed object - re-serialising JSON changes the bytes;
 *   <li>the comparison must be time-constant, otherwise the header becomes an oracle that lets an
 *       attacker recover a valid signature byte by byte;
 *   <li>an absent or malformed header must fail closed.
 * </ul>
 */
public final class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String secret;

    public WebhookSignatureVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A webhook secret is required to verify signatures");
        }
        this.secret = secret;
    }

    /**
     * @param payload the exact bytes GitHub sent
     * @param signatureHeader the value of {@code X-Hub-Signature-256}, including the prefix
     */
    public boolean isValid(byte[] payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
        byte[] presented = signatureHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    /** The header value Vestige would expect for this payload. */
    public String sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + toHex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                    "HmacSHA256 is required by every Java SE implementation", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
