package dev.youneskaouani.vestige.ingestion.sarif;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.matching.CandidateFinding;
import dev.youneskaouani.vestige.matching.FingerprintCalculator;
import dev.youneskaouani.vestige.matching.SourceLocation;
import dev.youneskaouani.vestige.matching.SourceSnapshot;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Turns an uploaded SARIF document into findings the matcher can work with.
 *
 * <p>Three things happen here that are worth knowing about:
 *
 * <ul>
 *   <li>file contents embedded in {@code run.artifacts[].contents.text} become the
 *       {@link SourceSnapshot} the fingerprints are computed against, which is how a report can
 *       enable the content-based matching passes without Vestige needing a checkout;
 *   <li>a result without its own {@code level} inherits the one from its rule's
 *       {@code defaultConfiguration}, which is where most analysers actually put it;
 *   <li>each finding gets a stable id derived from its position in the document, so that the
 *       matcher has a deterministic tiebreak and re-reading the same bytes gives the same ids.
 * </ul>
 */
public final class SarifReader {

    /**
     * {@code partialFingerprints} is a map because SARIF lets a tool publish several versioned
     * fingerprints at once. This is the order Vestige prefers them in; anything else falls back to
     * the lexicographically smallest key so that the choice is at least deterministic.
     */
    private static final List<String> PREFERRED_FINGERPRINT_KEYS =
            List.of("vestige/v1", "primaryLocationLineHash/v1", "primaryLocationLineHash");

    private static final String SECURITY_SEVERITY = "security-severity";

    private final ObjectMapper objectMapper;

    public SarifReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parses a report. Only the first run is read; SARIF's multi-run reports are not used here. */
    public AnalysisReport read(byte[] bytes) {
        SarifDocument document = parse(bytes);
        if (document.runs().isEmpty()) {
            throw new SarifParseException("SARIF document contains no runs");
        }
        SarifDocument.Run run = document.runs().get(0);

        SourceSnapshot snapshot = snapshotOf(run);
        FingerprintCalculator fingerprints = new FingerprintCalculator(snapshot);
        Map<String, String> defaultLevels = defaultLevelsByRuleId(run);
        List<String> artifactUris = artifactUris(run);

        List<CandidateFinding> findings = new ArrayList<>();
        List<SarifDocument.Result> results = run.results();
        for (int index = 0; index < results.size(); index++) {
            toFinding(results.get(index), index, defaultLevels, artifactUris, fingerprints)
                    .ifPresent(findings::add);
        }

        String name = Optional.ofNullable(run.tool())
                .map(SarifDocument.Tool::driver)
                .map(SarifDocument.Driver::name)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new SarifParseException("SARIF run does not name its tool"));
        String version = Optional.ofNullable(run.tool())
                .map(SarifDocument.Tool::driver)
                .map(SarifDocument.Driver::effectiveVersion)
                .orElse("unknown");

        return new AnalysisReport(name, version, findings, snapshot);
    }

    private SarifDocument parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new SarifParseException("SARIF report is empty");
        }
        try {
            SarifDocument document = objectMapper.readValue(bytes, SarifDocument.class);
            if (document == null) {
                throw new SarifParseException("SARIF report is empty");
            }
            return document;
        } catch (IOException e) {
            throw new SarifParseException("SARIF report could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * A result without a location cannot be tracked - there is nowhere to fingerprint and nothing
     * to show a reviewer - so it is dropped rather than turned into an issue that points nowhere.
     */
    private Optional<CandidateFinding> toFinding(
            SarifDocument.Result result,
            int index,
            Map<String, String> defaultLevels,
            List<String> artifactUris,
            FingerprintCalculator fingerprints) {

        if (result.ruleId() == null || result.ruleId().isBlank()) {
            return Optional.empty();
        }
        Optional<SourceLocation> location = locationOf(result, artifactUris);
        if (location.isEmpty()) {
            return Optional.empty();
        }

        String level = result.level() != null ? result.level() : defaultLevels.get(result.ruleId());
        Severity severity = Severity.fromSarif(level, securitySeverity(result.properties()));
        String message = Optional.ofNullable(result.message())
                .map(SarifDocument.Message::text)
                .orElse(result.ruleId());

        return Optional.of(new CandidateFinding(
                "r" + index,
                result.ruleId(),
                severity,
                message,
                location.get(),
                fingerprints.compute(
                        result.ruleId(), location.get(), preferredFingerprint(result.partialFingerprints()))));
    }

    private Optional<SourceLocation> locationOf(SarifDocument.Result result, List<String> artifactUris) {
        for (SarifDocument.Location location : result.locations()) {
            SarifDocument.PhysicalLocation physical = location.physicalLocation();
            if (physical == null || physical.artifactLocation() == null) {
                continue;
            }
            String uri = resolveUri(physical.artifactLocation(), artifactUris);
            if (uri == null || uri.isBlank()) {
                continue;
            }
            SarifDocument.Region region = physical.region();
            int startLine = region == null || region.startLine() == null ? 1 : region.startLine();
            if (startLine < 1) {
                startLine = 1;
            }
            int endLine = region == null || region.endLine() == null ? startLine : region.endLine();
            int startColumn = region == null || region.startColumn() == null ? 0 : region.startColumn();
            int endColumn = region == null || region.endColumn() == null ? 0 : region.endColumn();
            return Optional.of(new SourceLocation(uri, startLine, endLine, startColumn, endColumn));
        }
        return Optional.empty();
    }

    /** SARIF lets a location name a file either directly or by index into {@code run.artifacts}. */
    private String resolveUri(SarifDocument.ArtifactLocation location, List<String> artifactUris) {
        if (location.uri() != null && !location.uri().isBlank()) {
            return decodeUri(location.uri());
        }
        Integer index = location.index();
        if (index != null && index >= 0 && index < artifactUris.size()) {
            return artifactUris.get(index);
        }
        return null;
    }

    private SourceSnapshot snapshotOf(SarifDocument.Run run) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (SarifDocument.Artifact artifact : run.artifacts()) {
            if (artifact.location() == null || artifact.contents() == null) {
                continue;
            }
            String uri = artifact.location().uri();
            String text = artifact.contents().text();
            if (uri != null && !uri.isBlank() && text != null) {
                contents.put(decodeUri(uri), text);
            }
        }
        return contents.isEmpty() ? SourceSnapshot.empty() : SourceSnapshot.ofFileContents(contents);
    }

    private List<String> artifactUris(SarifDocument.Run run) {
        List<String> uris = new ArrayList<>(run.artifacts().size());
        for (SarifDocument.Artifact artifact : run.artifacts()) {
            String uri = artifact.location() == null ? null : artifact.location().uri();
            uris.add(uri == null ? "" : decodeUri(uri));
        }
        return uris;
    }

    private Map<String, String> defaultLevelsByRuleId(SarifDocument.Run run) {
        Map<String, String> levels = new LinkedHashMap<>();
        Optional.ofNullable(run.tool())
                .map(SarifDocument.Tool::driver)
                .map(SarifDocument.Driver::rules)
                .orElse(List.of())
                .forEach(rule -> {
                    if (rule.id() != null && rule.defaultConfiguration() != null) {
                        levels.put(rule.id(), rule.defaultConfiguration().level());
                    }
                });
        return levels;
    }

    private String preferredFingerprint(Map<String, String> partialFingerprints) {
        if (partialFingerprints.isEmpty()) {
            return null;
        }
        for (String key : PREFERRED_FINGERPRINT_KEYS) {
            String value = partialFingerprints.get(key);
            if (value != null && !value.isBlank()) {
                return key + ":" + value;
            }
        }
        String fallbackKey = new TreeSet<>(partialFingerprints.keySet()).first();
        String value = partialFingerprints.get(fallbackKey);
        return value == null || value.isBlank() ? null : fallbackKey + ":" + value;
    }

    /**
     * GitHub's code-scanning convention puts a CVSS-like score in a result property; it is the only
     * widely used way for a tool to say "this one is worse than the others" through SARIF's very
     * coarse four-level scale.
     */
    private Double securitySeverity(Map<String, Object> properties) {
        Object raw = properties.get(SECURITY_SEVERITY);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Strips the {@code file:} scheme and percent-decoding that some analysers emit. */
    private String decodeUri(String uri) {
        String value = uri;
        if (value.startsWith("file://")) {
            value = value.substring("file://".length());
        } else if (value.startsWith("file:")) {
            value = value.substring("file:".length());
        }
        if (value.indexOf('%') >= 0) {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return value;
    }
}
