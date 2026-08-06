package dev.youneskaouani.vestige.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.ingestion.sarif.AnalysisReport;
import dev.youneskaouani.vestige.ingestion.sarif.SarifParseException;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import dev.youneskaouani.vestige.matching.CandidateFinding;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SarifReaderTest {

    private final SarifReader reader = new SarifReader(new ObjectMapper());

    @Test
    @DisplayName("reads the tool, the findings and the embedded sources from a fixture")
    void readsAFixture() {
        AnalysisReport report = reader.read(SarifFixtures.bytes("commit-01-initial.sarif.json"));

        assertThat(report.analyserName()).isEqualTo("demo-java-analyzer");
        assertThat(report.analyserVersion()).isEqualTo("1.4.0");
        assertThat(report.findings()).hasSize(3);
        assertThat(report.hasEmbeddedSources()).isTrue();
        assertThat(report.findings())
                .extracting(CandidateFinding::ruleId)
                .containsExactly("java:S2259", "java:S2184", "java:S1181");
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.location().path())
                        .isEqualTo("src/main/java/com/example/OrderService.java"));
    }

    @Test
    @DisplayName("gives every finding a stable id derived from its position in the document")
    void assignsStableIds() {
        byte[] bytes = SarifFixtures.bytes("commit-01-initial.sarif.json");

        assertThat(reader.read(bytes).findings()).extracting(CandidateFinding::id)
                .containsExactly("r0", "r1", "r2");
        assertThat(reader.read(bytes).findings()).extracting(CandidateFinding::id)
                .isEqualTo(reader.read(bytes).findings().stream().map(CandidateFinding::id).toList());
    }

    @Test
    @DisplayName("computes content fingerprints from the embedded artifact contents")
    void computesFingerprintsFromEmbeddedSources() {
        AnalysisReport report = reader.read(SarifFixtures.bytes("commit-01-initial.sarif.json"));

        assertThat(report.findings())
                .allSatisfy(finding -> {
                    assertThat(finding.fingerprints().structural()).isNotNull();
                    assertThat(finding.fingerprints().lineContent()).isNotNull();
                });
    }

    @Test
    @DisplayName("reads the analyser's own fingerprints when the report supplies them")
    void readsPartialFingerprints() {
        AnalysisReport report = reader.read(SarifFixtures.bytes("commit-04-regression.sarif.json"));

        assertThat(report.findings())
                .extracting(f -> f.fingerprints().exact())
                .allSatisfy(value -> assertThat(value).startsWith("primaryLocationLineHash/v1:"));
    }

    @Test
    @DisplayName("promotes a result to BLOCKER when the tool reports a high security severity")
    void refinesSeverityFromSecuritySeverity() {
        AnalysisReport report = reader.read(SarifFixtures.bytes("commit-01-initial.sarif.json"));

        assertThat(report.findings().get(0).severity()).isEqualTo(Severity.BLOCKER);
        assertThat(report.findings().get(1).severity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    @DisplayName("falls back to the rule's default level when a result omits its own")
    void inheritsRuleDefaultLevel() {
        String sarif = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "tool", "version": "1.0", "rules": [
                      {"id": "R1", "defaultConfiguration": {"level": "error"}}
                    ]}},
                    "results": [{
                      "ruleId": "R1",
                      "message": {"text": "boom"},
                      "locations": [{"physicalLocation": {
                        "artifactLocation": {"uri": "a.java"}, "region": {"startLine": 3}}}]
                    }]
                  }]
                }
                """;

        AnalysisReport report = reader.read(sarif.getBytes(StandardCharsets.UTF_8));

        assertThat(report.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("resolves a location that names its file by artifact index")
    void resolvesArtifactIndex() {
        String sarif = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "tool", "version": "1.0"}},
                    "artifacts": [{"location": {"uri": "src/Indexed.java"}}],
                    "results": [{
                      "ruleId": "R1",
                      "level": "warning",
                      "message": {"text": "boom"},
                      "locations": [{"physicalLocation": {
                        "artifactLocation": {"index": 0}, "region": {"startLine": 2}}}]
                    }]
                  }]
                }
                """;

        AnalysisReport report = reader.read(sarif.getBytes(StandardCharsets.UTF_8));

        assertThat(report.findings().get(0).location().path()).isEqualTo("src/Indexed.java");
    }

    @Test
    @DisplayName("normalises file: URIs and percent-encoding")
    void normalisesUris() {
        String sarif = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "tool", "version": "1.0"}},
                    "results": [{
                      "ruleId": "R1",
                      "level": "warning",
                      "message": {"text": "boom"},
                      "locations": [{"physicalLocation": {
                        "artifactLocation": {"uri": "file:///src/my%20app/Main.java"},
                        "region": {"startLine": 2}}}]
                    }]
                  }]
                }
                """;

        AnalysisReport report = reader.read(sarif.getBytes(StandardCharsets.UTF_8));

        assertThat(report.findings().get(0).location().path()).isEqualTo("src/my app/Main.java");
    }

    @Test
    @DisplayName("ignores results that cannot be tracked instead of inventing a location")
    void dropsUntrackableResults() {
        String sarif = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "tool", "version": "1.0"}},
                    "results": [
                      {"ruleId": "R1", "level": "warning", "message": {"text": "no location"}},
                      {"level": "warning", "message": {"text": "no rule"},
                       "locations": [{"physicalLocation": {
                         "artifactLocation": {"uri": "a.java"}, "region": {"startLine": 1}}}]},
                      {"ruleId": "R2", "level": "warning", "message": {"text": "fine"},
                       "locations": [{"physicalLocation": {
                         "artifactLocation": {"uri": "a.java"}, "region": {"startLine": 4}}}]}
                    ]
                  }]
                }
                """;

        AnalysisReport report = reader.read(sarif.getBytes(StandardCharsets.UTF_8));

        assertThat(report.findings()).extracting(CandidateFinding::ruleId).containsExactly("R2");
        assertThat(report.findings().get(0).id()).isEqualTo("r2");
    }

    @Test
    @DisplayName("tolerates unknown properties rather than rejecting a valid report")
    void ignoresUnknownProperties() {
        String sarif = """
                {
                  "version": "2.1.0",
                  "inlineExternalProperties": [{"guid": "x"}],
                  "runs": [{
                    "automationDetails": {"id": "build/42"},
                    "tool": {"driver": {"name": "tool", "version": "1.0", "language": "en-US"}},
                    "results": [{
                      "ruleId": "R1", "level": "warning", "kind": "fail", "baselineState": "new",
                      "message": {"text": "boom", "markdown": "**boom**"},
                      "locations": [{"physicalLocation": {
                        "artifactLocation": {"uri": "a.java"}, "region": {"startLine": 1}}}]
                    }]
                  }]
                }
                """;

        assertThat(reader.read(sarif.getBytes(StandardCharsets.UTF_8)).findings()).hasSize(1);
    }

    @Test
    @DisplayName("rejects input that is not a usable SARIF report")
    void rejectsBadInput() {
        assertThatThrownBy(() -> reader.read(new byte[0]))
                .isInstanceOf(SarifParseException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> reader.read("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SarifParseException.class)
                .hasMessageContaining("could not be read");

        assertThatThrownBy(() -> reader.read("{\"version\":\"2.1.0\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SarifParseException.class)
                .hasMessageContaining("no runs");

        String noTool = "{\"version\":\"2.1.0\",\"runs\":[{\"results\":[]}]}";
        assertThatThrownBy(() -> reader.read(noTool.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SarifParseException.class)
                .hasMessageContaining("does not name its tool");
    }
}
