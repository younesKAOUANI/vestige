package dev.youneskaouani.vestige.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Comparisons whose duration does not depend on where the first differing byte is. */
public final class ConstantTime {

    private ConstantTime() {
    }

    /**
     * Compares two secrets without leaking their contents through timing.
     *
     * <p>{@link MessageDigest#isEqual(byte[], byte[])} has been time-constant since Java 7 and is
     * the standard primitive for this; {@link String#equals(Object)} short-circuits on the first
     * mismatching character and must never be used for API keys or HMACs.
     */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
