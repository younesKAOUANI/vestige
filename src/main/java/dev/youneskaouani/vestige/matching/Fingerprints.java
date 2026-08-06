package dev.youneskaouani.vestige.matching;

/**
 * The three content-derived identities of a finding, one per fingerprint-based pass.
 *
 * <p>Any of them may be {@code null}: an analyser that does not emit {@code partialFingerprints}
 * leaves {@link #exact()} empty, and a report without embedded file contents leaves
 * {@link #structural()} and {@link #lineContent()} empty. A null fingerprint means "this pass has
 * nothing to say", never "matches anything".
 */
public record Fingerprints(String exact, String structural, String lineContent) {

    private static final Fingerprints NONE = new Fingerprints(null, null, null);

    public Fingerprints {
        exact = blankToNull(exact);
        structural = blankToNull(structural);
        lineContent = blankToNull(lineContent);
    }

    public static Fingerprints none() {
        return NONE;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
