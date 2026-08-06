package dev.youneskaouani.vestige.ingestion.sarif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.matching.FingerprintFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SarifReader} against hand-written SARIF fragments, including the field-order
 * cases the class javadoc calls out: a result can stream past before the {@code tool}/{@code
 * artifacts} sibling data it needs to resolve fully has been seen.
 */
class SarifReaderTest {

    private final SarifReader reader = new SarifReader(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads rule id, level, message, location and snippet from a minimal, well-formed result")
    void readsACompleteResult() {
        String sarif =
                """
                {
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": { "driver": { "name": "ESLint", "version": "8.1.0" } },
                      "results": [
                        {
                          "ruleId": "no-unused-vars",
                          "level": "error",
                          "message": { "text": "'x' is never used" },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "src/index.js" },
                                "region": {
                                  "startLine": 12,
                                  "endLine": 12,
                                  "startColumn": 3,
                                  "endColumn": 10,
                                  "snippet": { "text": "let x = 1;" }
                                }
                              },
                              "logicalLocations": [ { "fullyQualifiedName": "module.exports#run" } ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.analyserName()).isEqualTo("ESLint");
        assertThat(report.analyserVersion()).isEqualTo("8.1.0");
        assertThat(report.findings()).hasSize(1);

        RawFinding finding = report.findings().get(0);
        assertThat(finding.ordinal()).isZero();
        assertThat(finding.ruleId()).isEqualTo("no-unused-vars");
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL); // "error" -> CRITICAL
        assertThat(finding.message()).isEqualTo("'x' is never used");
        assertThat(finding.filePath()).isEqualTo("src/index.js");
        assertThat(finding.symbolPath()).isEqualTo("module.exports#run");
        assertThat(finding.startLine()).isEqualTo(12);
        assertThat(finding.endLine()).isEqualTo(12);
        assertThat(finding.startColumn()).isEqualTo(3);
        assertThat(finding.endColumn()).isEqualTo(10);
        assertThat(finding.lineSnippet()).isEqualTo("let x = 1;");
        assertThat(finding.fingerprints())
                .isEqualTo(FingerprintFactory.compute(
                        "no-unused-vars", "src/index.js", "module.exports#run", "let x = 1;"));
    }

    @Test
    @DisplayName("semanticVersion is preferred over version when both are present")
    void prefersSemanticVersion() {
        String sarif = toolOnlySarif(
                """
                { "driver": { "name": "CodeQL", "version": "2.15.0", "semanticVersion": "2.15.3+build.7" } }
                """);

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.analyserVersion()).isEqualTo("2.15.3+build.7");
    }

    @Test
    @DisplayName("a result with no level falls back to its rule's defaultConfiguration.level")
    void fallsBackToRuleDefaultLevel() {
        String sarif =
                """
                {
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "Analyser",
                          "rules": [
                            { "id": "R1", "defaultConfiguration": { "level": "error" } }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "R1",
                          "message": { "text": "m" },
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "uri": "A.java" } } }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("a result with neither its own level nor a rule default falls back to Severity.fromSarif's default")
    void fallsBackToDefaultSeverityWhenNothingIsReported() {
        String sarif = singleResultSarif(
                "\"ruleId\": \"R1\", \"locations\": [ { \"physicalLocation\": "
                        + "{ \"artifactLocation\": { \"uri\": \"A.java\" } } } ]");

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings().get(0).severity()).isEqualTo(Severity.fromSarif(null, null));
        assertThat(report.findings().get(0).message())
                .as("no message.text supplied: falls back to the rule id")
                .isEqualTo("R1");
    }

    @Test
    @DisplayName("ruleIndex resolves the rule id when ruleId itself is absent")
    void resolvesRuleIdFromRuleIndex() {
        String sarif =
                """
                {
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "Analyser",
                          "rules": [ { "id": "R0" }, { "id": "R1" } ]
                        }
                      },
                      "results": [
                        {
                          "ruleIndex": 1,
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "uri": "A.java" } } }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).ruleId()).isEqualTo("R1");
    }

    @Test
    @DisplayName("a region-less result defaults to line 1, and endLine defaults to startLine")
    void defaultsLineNumbersWhenRegionIsAbsent() {
        String sarif = singleResultSarif(
                "\"ruleId\": \"R1\", \"locations\": [ { \"physicalLocation\": "
                        + "{ \"artifactLocation\": { \"uri\": \"A.java\" } } } ]");

        RawFinding finding = reader.read(bytes(sarif)).findings().get(0);

        assertThat(finding.startLine()).isEqualTo(1);
        assertThat(finding.endLine()).isEqualTo(1);
        assertThat(finding.startColumn()).isZero();
        assertThat(finding.endColumn()).isZero();
    }

    @Test
    @DisplayName("a result with no rule id at all is dropped, not turned into an untraceable finding")
    void dropsResultsWithoutARuleId() {
        String sarif = singleResultSarif(
                "\"message\": { \"text\": \"m\" }, \"locations\": [ { \"physicalLocation\": "
                        + "{ \"artifactLocation\": { \"uri\": \"A.java\" } } } ]");

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    @DisplayName("a result with no usable location is dropped")
    void dropsResultsWithoutALocation() {
        String sarif = singleResultSarif("\"ruleId\": \"R1\", \"message\": { \"text\": \"m\" }");

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    @DisplayName("an index-based artifact location resolves against run.artifacts, and file: URIs are decoded")
    void resolvesArtifactIndexAndDecodesFileUris() {
        String sarif =
                """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "Analyser" } },
                      "artifacts": [
                        { "location": { "uri": "file:///repo/src/A%20B.java" } }
                      ],
                      "results": [
                        {
                          "ruleId": "R1",
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "index": 0 } } }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        RawFinding finding = reader.read(bytes(sarif)).findings().get(0);

        assertThat(finding.filePath()).isEqualTo("/repo/src/A B.java");
    }

    @Test
    @DisplayName("artifacts declared AFTER results in the document still resolve correctly, in the original order")
    void resolvesWhenArtifactsFollowResultsInDocumentOrder() {
        String sarif =
                """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "Analyser" } },
                      "results": [
                        {
                          "ruleId": "R-BY-URI",
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "uri": "Direct.java" } } }
                          ]
                        },
                        {
                          "ruleId": "R-BY-INDEX",
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "index": 0 } } }
                          ]
                        }
                      ],
                      "artifacts": [
                        { "location": { "uri": "ByIndex.java" } }
                      ]
                    }
                  ]
                }
                """;

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.findings()).hasSize(2);
        // Document order is preserved even though the second finding's dependency (artifacts)
        // streamed past after both results did - see the class javadoc's "Field order" section.
        assertThat(report.findings().get(0).ordinal()).isZero();
        assertThat(report.findings().get(0).ruleId()).isEqualTo("R-BY-URI");
        assertThat(report.findings().get(0).filePath()).isEqualTo("Direct.java");
        assertThat(report.findings().get(1).ordinal()).isEqualTo(1);
        assertThat(report.findings().get(1).ruleId()).isEqualTo("R-BY-INDEX");
        assertThat(report.findings().get(1).filePath()).isEqualTo("ByIndex.java");
    }

    @Test
    @DisplayName("a security-severity of 9.0 or above elevates the finding to BLOCKER regardless of level")
    void securitySeverityElevatesToBlocker() {
        String sarif = singleResultSarif(
                "\"ruleId\": \"R1\", \"level\": \"warning\", \"properties\": { \"security-severity\": \"9.8\" }, "
                        + "\"locations\": [ { \"physicalLocation\": { \"artifactLocation\": { \"uri\": \"A.java\" } } } ]");

        RawFinding finding = reader.read(bytes(sarif)).findings().get(0);

        assertThat(finding.severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    @DisplayName("the first location with a resolvable artifact wins when a result reports several")
    void firstResolvableLocationWins() {
        String sarif =
                """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "Analyser" } },
                      "results": [
                        {
                          "ruleId": "R1",
                          "locations": [
                            { "physicalLocation": { "artifactLocation": { "index": 99 } } },
                            { "physicalLocation": { "artifactLocation": { "uri": "Second.java" } } }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        RawFinding finding = reader.read(bytes(sarif)).findings().get(0);

        assertThat(finding.filePath())
                .as("index 99 does not resolve against an empty artifacts list, so the next location is used")
                .isEqualTo("Second.java");
    }

    @Test
    @DisplayName("findings are delivered to the batch consumer in order and in batches of the given size")
    void deliversBatchesInOrder() {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                results.append(",");
            }
            results.append(
                    """
                    { "ruleId": "R%d", "locations": [ { "physicalLocation":
                      { "artifactLocation": { "uri": "F%d.java" } } } ] }
                    """
                            .formatted(i, i));
        }
        String sarif = """
                { "runs": [ { "tool": { "driver": { "name": "Analyser" } }, "results": [ %s ] } ] }
                """
                .formatted(results);

        List<List<RawFinding>> batches = new ArrayList<>();
        AnalysisReport report = reader.read(bytes(sarif), 2, batches::add);

        assertThat(report.findings()).hasSize(5);
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(2);
        assertThat(batches.get(1)).hasSize(2);
        assertThat(batches.get(2)).hasSize(1);
        assertThat(batches.stream().flatMap(List::stream).map(RawFinding::ruleId))
                .containsExactly("R0", "R1", "R2", "R3", "R4");
    }

    @Test
    @DisplayName("rejects an empty payload")
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> reader.read(new byte[0])).isInstanceOf(SarifParseException.class);
    }

    @Test
    @DisplayName("rejects a document with no runs")
    void rejectsNoRuns() {
        assertThatThrownBy(() -> reader.read(bytes("{ \"runs\": [] }")))
                .isInstanceOf(SarifParseException.class);
    }

    @Test
    @DisplayName("rejects a run that never names its tool")
    void rejectsAMissingToolName() {
        assertThatThrownBy(() -> reader.read(bytes("{ \"runs\": [ { \"results\": [] } ] }")))
                .isInstanceOf(SarifParseException.class);
    }

    @Test
    @DisplayName("unknown top-level and run-level fields are ignored rather than rejected")
    void ignoresUnknownFields() {
        String sarif =
                """
                {
                  "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/sarif-2.1/schema/sarif-schema-2.1.0.json",
                  "version": "2.1.0",
                  "someVendorExtension": { "anything": [1, 2, 3] },
                  "runs": [
                    {
                      "tool": { "driver": { "name": "Analyser" } },
                      "originalUriBaseIds": { "SRCROOT": { "uri": "file:///repo/" } },
                      "results": []
                    }
                  ]
                }
                """;

        AnalysisReport report = reader.read(bytes(sarif));

        assertThat(report.analyserName()).isEqualTo("Analyser");
        assertThat(report.findings()).isEmpty();
    }

    private static String toolOnlySarif(String toolJson) {
        return """
                { "runs": [ { "tool": %s, "results": [] } ] }
                """
                .formatted(toolJson);
    }

    private static String singleResultSarif(String resultBody) {
        return """
                { "runs": [ { "tool": { "driver": { "name": "Analyser" } }, "results": [ { %s } ] } ] }
                """
                .formatted(resultBody);
    }
}
