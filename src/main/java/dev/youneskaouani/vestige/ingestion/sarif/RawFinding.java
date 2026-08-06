package dev.youneskaouani.vestige.ingestion.sarif;

import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.matching.Fingerprints;

/**
 * One SARIF {@code result}, reduced to what persistence and matching need and nothing else -
 * deliberately shaped to be an almost direct write into the {@code finding} table (§2.1).
 *
 * @param ordinal this result's position in the run's parse order (§4.2's determinism requirement:
 *     the same report bytes always yield the same ordinals in the same order); becomes {@code
 *     IncomingFinding.ordinal()} for the matcher
 * @param ruleId the analyser rule id, e.g. {@code java:S3649}
 * @param severity resolved from the result's own {@code level}, its rule's {@code
 *     defaultConfiguration.level}, or the GitHub {@code security-severity} property, in that
 *     preference order (see {@link Severity#fromSarif})
 * @param message human-readable text for the UI; the rule id when the result supplied none
 * @param filePath the flagged file, exactly as reported (or resolved from an artifact index) -
 *     normalised later, at fingerprint time, not here
 * @param symbolPath the first {@code logicalLocations[].fullyQualifiedName} on the result's
 *     primary location, or {@code null} when the analyser did not supply one
 * @param startLine 1-based; SARIF permits omitting the region entirely, in which case this is 1
 * @param endLine defaults to {@code startLine} when the analyser did not supply one
 * @param startColumn 0 when not reported
 * @param endColumn 0 when not reported
 * @param lineSnippet {@code region.snippet.text} verbatim, or {@code null}
 * @param fingerprints computed once, here, at parse time (§3.2)
 */
public record RawFinding(
        int ordinal,
        String ruleId,
        Severity severity,
        String message,
        String filePath,
        String symbolPath,
        int startLine,
        int endLine,
        int startColumn,
        int endColumn,
        String lineSnippet,
        Fingerprints fingerprints) {
}
