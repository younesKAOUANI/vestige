package dev.youneskaouani.vestige.matching;

/**
 * The three fingerprints §3.2 computes for a finding. Any of {@code identityFp}/{@code contextFp}
 * may be {@code null} when the finding does not carry the input that rung needs (no symbol path, no
 * line snippet); {@code weakFp} is always present, since it needs only the rule id and the file
 * path, both of which are mandatory on any usable finding.
 */
public record Fingerprints(String identityFp, String contextFp, String weakFp) {

    public Fingerprints {
        if (weakFp == null || weakFp.isBlank()) {
            throw new IllegalArgumentException(
                    "weakFp is mandatory: rule id and file path are always available");
        }
    }
}
