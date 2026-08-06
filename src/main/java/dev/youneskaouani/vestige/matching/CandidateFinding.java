package dev.youneskaouani.vestige.matching;

import dev.youneskaouani.vestige.common.domain.Severity;
import java.util.Comparator;

/**
 * A single result from the incoming report, ready to be matched.
 *
 * <p>{@code id} is assigned by the caller and only has to be unique within one request; the
 * ingestion pipeline uses the result's index in the SARIF document, which is stable for a given
 * report. It exists so that the matcher has a total order to fall back on and can therefore be
 * order-independent even when two findings are identical in every other respect.
 */
public record CandidateFinding(
        String id,
        String ruleId,
        Severity severity,
        String message,
        SourceLocation location,
        Fingerprints fingerprints) {

    /**
     * A total order over findings. Every field takes part, so distinct findings never compare
     * equal, which is what makes the matcher's output independent of input order.
     */
    public static final Comparator<CandidateFinding> CANONICAL_ORDER =
            Comparator.comparing((CandidateFinding f) -> f.location().path())
                    .thenComparingInt(f -> f.location().startLine())
                    .thenComparingInt(f -> f.location().startColumn())
                    .thenComparing(CandidateFinding::ruleId)
                    .thenComparing(f -> f.message() == null ? "" : f.message())
                    .thenComparing(CandidateFinding::id);

    public CandidateFinding {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("CandidateFinding requires an id");
        }
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("CandidateFinding requires a ruleId");
        }
        if (location == null) {
            throw new IllegalArgumentException("CandidateFinding requires a location");
        }
        if (severity == null) {
            severity = Severity.MAJOR;
        }
        if (fingerprints == null) {
            fingerprints = Fingerprints.none();
        }
    }
}
