package dev.youneskaouani.vestige.ingestion.sarif;

import dev.youneskaouani.vestige.matching.CandidateFinding;
import dev.youneskaouani.vestige.matching.SourceSnapshot;
import java.util.List;

/**
 * A SARIF report, reduced to what the rest of Vestige works with.
 *
 * @param analyserName the tool that produced the report
 * @param analyserVersion its version, used to explain a sudden change in findings
 * @param findings every result, already carrying its fingerprints
 * @param snapshot the file contents the report embedded, if any
 */
public record AnalysisReport(
        String analyserName,
        String analyserVersion,
        List<CandidateFinding> findings,
        SourceSnapshot snapshot) {

    public AnalysisReport {
        findings = List.copyOf(findings);
    }

    /** True when the report embedded file contents, which is what enables passes 3 and 4. */
    public boolean hasEmbeddedSources() {
        return findings.stream().anyMatch(f -> f.fingerprints().structural() != null);
    }
}
