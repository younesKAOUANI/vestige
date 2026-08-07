package dev.youneskaouani.vestige.matching;

import java.util.function.Function;

/**
 * The three rungs of §3.3's ladder, in the exact order they are tried: strong evidence first, weak
 * evidence last, each completing fully before the next begins.
 */
public enum Rung {
    IDENTITY(Fingerprints::identityFp),
    CONTEXT(Fingerprints::contextFp),
    WEAK(Fingerprints::weakFp);

    private final Function<Fingerprints, String> extractor;

    Rung(Function<Fingerprints, String> extractor) {
        this.extractor = extractor;
    }

    /** The bucket key this rung uses for {@code fingerprints}, or {@code null} if inapplicable. */
    public String fingerprintOf(Fingerprints fingerprints) {
        return extractor.apply(fingerprints);
    }
}
