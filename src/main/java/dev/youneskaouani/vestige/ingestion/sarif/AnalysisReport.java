package dev.youneskaouani.vestige.ingestion.sarif;

import java.util.List;

/**
 * A SARIF report, reduced to what the rest of Vestige works with.
 *
 * @param analyserName the tool that produced the report
 * @param analyserVersion its version, used to explain a sudden change in findings
 * @param findings every usable result, in parse order, already carrying its fingerprints
 */
public record AnalysisReport(
        String analyserName, String analyserVersion, List<RawFinding> findings) {

    public AnalysisReport {
        findings = List.copyOf(findings);
    }
}
