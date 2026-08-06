package dev.youneskaouani.vestige.matching;

import dev.youneskaouani.vestige.common.hash.Sha256;

/**
 * Computes §3.2's fingerprint ladder from a finding's raw fields.
 *
 * <p>This is the one piece of matching logic that runs twice for two different reasons. For a
 * <em>current</em> finding, it runs once, at parse time, over the path exactly as the analyser
 * reported it. For a <em>previous</em> issue's most recent finding, it is recomputed at match time
 * (§3.3, {@code IssueMatchingService}) over the path <em>after</em> the commit's rename map has
 * been applied - the stored fingerprint columns on {@code finding} reflect the path as of that
 * finding's own run, not this one, so reusing them directly would silently break every renamed
 * file's matching. Being a pure function of its inputs is what makes recomputation on demand cheap
 * and correct rather than a second thing to keep in sync.
 */
public final class FingerprintFactory {

    private FingerprintFactory() {
    }

    /**
     * @param ruleId the analyser rule id, e.g. {@code java:S3649}
     * @param rawFilePath the file path as reported (or, for a previous issue, after rename
     *     resolution) - normalised internally
     * @param symbolPath the enclosing declaration chain from SARIF {@code logicalLocations}, or
     *     {@code null}/blank when the analyser did not supply one
     * @param rawLineSnippet the literal text of the flagged line, or {@code null}/blank when the
     *     analyser did not supply {@code region.snippet.text}
     */
    public static Fingerprints compute(String ruleId, String rawFilePath, String symbolPath, String rawLineSnippet) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId is required to compute a fingerprint");
        }
        String path = PathNormalizer.normalize(rawFilePath);

        String identityFp = (symbolPath == null || symbolPath.isBlank())
                ? null
                : Sha256.hexOfFields(ruleId, path, symbolPath);

        String normalizedLine = LineNormalizer.normalize(rawLineSnippet);
        String contextFp = (normalizedLine == null || normalizedLine.isEmpty())
                ? null
                : Sha256.hexOfFields(ruleId, path, normalizedLine);

        String weakFp = Sha256.hexOfFields(ruleId, path);

        return new Fingerprints(identityFp, contextFp, weakFp);
    }
}
