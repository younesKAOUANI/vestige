package dev.youneskaouani.vestige.ingestion.sarif;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The subset of the SARIF 2.1.0 object model that Vestige reads.
 *
 * <p>These records are a reading surface, not a specification: everything unknown is ignored, and
 * every collection defaults to empty. SARIF is a large format with a great deal of optionality, and
 * a strict binding would reject perfectly good reports over a property nobody needs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SarifDocument(String version, List<Run> runs) {

    public SarifDocument {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Run(Tool tool, List<Result> results, List<Artifact> artifacts) {
        public Run {
            results = results == null ? List.of() : List.copyOf(results);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool(Driver driver) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Driver(String name, String version, String semanticVersion, List<Rule> rules) {
        public Driver {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        /** SARIF allows either field; {@code semanticVersion} is the more precise one when present. */
        public String effectiveVersion() {
            if (semanticVersion != null && !semanticVersion.isBlank()) {
                return semanticVersion;
            }
            return version == null || version.isBlank() ? "unknown" : version;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String id, DefaultConfiguration defaultConfiguration, Map<String, Object> properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefaultConfiguration(String level) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            String ruleId,
            Integer ruleIndex,
            String level,
            Message message,
            List<Location> locations,
            Map<String, String> partialFingerprints,
            Map<String, Object> properties) {

        public Result {
            locations = locations == null ? List.of() : List.copyOf(locations);
            partialFingerprints =
                    partialFingerprints == null ? Map.of() : Map.copyOf(partialFingerprints);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(PhysicalLocation physicalLocation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhysicalLocation(ArtifactLocation artifactLocation, Region region) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactLocation(String uri, Integer index) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Region(Integer startLine, Integer endLine, Integer startColumn, Integer endColumn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(ArtifactLocation location, ArtifactContent contents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactContent(String text) {
    }
}
