package dev.youneskaouani.vestige.common.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Thin, allocation-conscious wrapper around SHA-256.
 *
 * <p>Every hash Vestige persists (report digests, issue fingerprints, audit chain entries) goes
 * through this class so that the encoding is identical everywhere: UTF-8 input, lowercase
 * hexadecimal output.
 */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** ASCII record separator: cannot occur inside the values we hash, so it delimits fields safely. */
    private static final byte FIELD_SEPARATOR = 0x1E;

    private Sha256() {
    }

    /** Returns the lowercase hex SHA-256 of {@code value} encoded as UTF-8. */
    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the lowercase hex SHA-256 of the given bytes. */
    public static String hex(byte[] value) {
        return toHex(digest().digest(value));
    }

    /**
     * Hashes several fields as one value while keeping the boundaries between fields unambiguous.
     *
     * <p>Naive concatenation is a real (if unglamorous) source of collisions: {@code ("ab", "c")}
     * and {@code ("a", "bc")} would otherwise hash identically, which for issue fingerprints means
     * two unrelated findings silently becoming "the same issue". Each field is therefore prefixed
     * with its UTF-8 byte length.
     */
    public static String hexOfFields(String... fields) {
        MessageDigest digest = digest();
        for (String field : fields) {
            byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update(FIELD_SEPARATOR);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE implementation", e);
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
