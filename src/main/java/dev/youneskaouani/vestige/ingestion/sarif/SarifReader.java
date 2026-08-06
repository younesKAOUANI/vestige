package dev.youneskaouani.vestige.ingestion.sarif;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.matching.FingerprintFactory;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns an uploaded SARIF 2.1.0 report into the findings the rest of Vestige works with.
 *
 * <p>Reads with Jackson's streaming (pull) API rather than {@code ObjectMapper.readValue}: a
 * report can be up to {@code vestige.ingestion.max-report-bytes} (200 MB by default), and binding
 * that to a full object graph before extracting a handful of fields per result would hold several
 * times its size in Java objects for no benefit. {@link JsonParser#nextToken()} instead walks the
 * document's shape directly; {@link ObjectMapper#readTree(JsonParser)} is used only for small,
 * bounded sub-objects - one {@code tool}, one {@code artifacts[i]}, one {@code results[i]} at a
 * time - which is the standard, documented way to mix Jackson's streaming and tree APIs, and is
 * never asked to hold the {@code results} array (the one that can be large) as a whole.
 *
 * <p>Only the first {@code run} in {@code runs} is read; SARIF's multi-run reports are not used
 * here.
 *
 * <h2>Field order and batching</h2>
 *
 * <p>Resolving a result fully needs two things that are <em>siblings</em> of {@code results}
 * within the same {@code run} object: {@code tool.driver.rules[].defaultConfiguration.level}
 * (when a result omits its own {@code level}), and {@code artifacts[].location.uri} (when a
 * location references an artifact by {@code index} rather than inlining {@code uri} directly).
 * Because SARIF does not guarantee field order, a result can stream past before the sibling it
 * would need has been seen - so results are first reduced, one bounded {@code JsonNode} at a time,
 * to a small pending record that keeps everything unresolved (rule id/index, level-or-absent,
 * uri-or-index), and resolution happens in one final pass once the whole run object has been read
 * and {@code tool}/{@code artifacts} are known for good. That final pass is also where {@code
 * batchConsumer} is invoked every {@code batchSize} findings - see {@link #read(byte[], int,
 * Consumer)}. The trade-off this makes deliberately: JSON parsing is fully streaming end to end
 * (no full-document tree, no whole-array binding, one small node in memory at a time), while the
 * database-batching pass runs after it rather than perfectly interleaved with token consumption.
 * The alternative - flushing eagerly whenever a result's dependencies happen to already be known -
 * was tried and rejected: it made the batches' (and therefore the persisted {@code finding.seq}
 * identity column's) order depend on where {@code tool}/{@code artifacts} happen to sit in the
 * document, silently breaking the "lowest finding id" tie-break's connection to parse order for
 * exactly the reports where field order is least conventional. A single, always-in-order pass is
 * worth the small amount of buffering it costs.
 */
public final class SarifReader {

    private static final String SECURITY_SEVERITY = "security-severity";

    private final ObjectMapper objectMapper;

    public SarifReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** {@link #read(byte[], int, Consumer)} with no batching - convenient for tests and small reports. */
    public AnalysisReport read(byte[] sarif) {
        return read(sarif, Integer.MAX_VALUE, batch -> {
        });
    }

    /**
     * Reads only as far as the first run's {@code tool.driver} name and version, then stops -
     * never touching {@code results} or {@code artifacts}, however large they are.
     *
     * <p>Exists for one reason: {@code POST /api/v1/runs} needs the analyser name synchronously,
     * to compute §4.1's idempotency fallback key ({@code sha256(project‖commit‖analyser‖
     * report_digest)}) and to satisfy {@code analysis_run.analyser_name}'s {@code NOT NULL}
     * constraint, before the run is even queued - but the full parse (§4.2) deliberately happens
     * later, on the worker, off the request thread. This method is what lets both of those be true
     * at once without parsing the report's expensive part twice.
     *
     * @throws SarifParseException the bytes are not readable, or the first run does not name a tool
     */
    public ToolIdentity peekToolIdentity(byte[] sarif) {
        if (sarif == null || sarif.length == 0) {
            throw new SarifParseException("SARIF report is empty");
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(sarif)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SarifParseException("SARIF report is not a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("runs".equals(field)) {
                    return peekToolFromRuns(parser);
                }
                skipValue(parser);
            }
            throw new SarifParseException("SARIF document contains no runs");
        } catch (IOException e) {
            throw new SarifParseException("SARIF report could not be read: " + e.getMessage(), e);
        }
    }

    private ToolIdentity peekToolFromRuns(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw new SarifParseException("SARIF \"runs\" is not an array");
        }
        if (parser.nextToken() == JsonToken.END_ARRAY) {
            throw new SarifParseException("SARIF document contains no runs");
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new SarifParseException("SARIF run is not an object");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if ("tool".equals(field)) {
                ToolInfo tool = readTool(parser);
                if (tool.name() == null || tool.name().isBlank()) {
                    throw new SarifParseException("SARIF run does not name its tool");
                }
                return new ToolIdentity(tool.name(), tool.effectiveVersion());
            }
            skipValue(parser);
        }
        throw new SarifParseException("SARIF run does not name its tool");
    }

    /** The two fields {@link #peekToolIdentity} bothers to read. */
    public record ToolIdentity(String name, String version) {
    }

    /**
     * Parses {@code sarif}, delivering every usable finding to {@code batchConsumer} in parse
     * order, in batches of at most {@code batchSize} - where the caller's JDBC/JPA batch insert
     * should happen (§4.2's "batched inserts of 1,000") - and also returning the complete,
     * ordered report, which the matcher (§3.3) needs as a whole regardless of how it was written.
     *
     * @throws SarifParseException the bytes are not a readable SARIF document, or its first run
     *     does not name a tool
     */
    public AnalysisReport read(byte[] sarif, int batchSize, Consumer<List<RawFinding>> batchConsumer) {
        if (sarif == null || sarif.length == 0) {
            throw new SarifParseException("SARIF report is empty");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(sarif)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SarifParseException("SARIF report is not a JSON object");
            }
            return readDocument(parser, batchSize, batchConsumer);
        } catch (IOException e) {
            throw new SarifParseException("SARIF report could not be read: " + e.getMessage(), e);
        }
    }

    private AnalysisReport readDocument(
            JsonParser parser, int batchSize, Consumer<List<RawFinding>> batchConsumer) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if ("runs".equals(field)) {
                return readRuns(parser, batchSize, batchConsumer);
            }
            skipValue(parser);
        }
        throw new SarifParseException("SARIF document contains no runs");
    }

    private AnalysisReport readRuns(JsonParser parser, int batchSize, Consumer<List<RawFinding>> batchConsumer)
            throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw new SarifParseException("SARIF \"runs\" is not an array");
        }
        if (parser.nextToken() == JsonToken.END_ARRAY) {
            throw new SarifParseException("SARIF document contains no runs");
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new SarifParseException("SARIF run is not an object");
        }
        // Only the first run is read. try-with-resources in read() closes the parser as soon as
        // this returns, so any further runs in the array are never even looked at.
        return readRun(parser, batchSize, batchConsumer);
    }

    private AnalysisReport readRun(JsonParser parser, int batchSize, Consumer<List<RawFinding>> batchConsumer)
            throws IOException {
        ToolInfo tool = null;
        List<String> artifactUris = List.of();
        List<PendingResult> pending = new ArrayList<>();
        int ordinal = 0;

        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "tool" -> tool = readTool(parser);
                case "artifacts" -> artifactUris = readArtifactUris(parser);
                case "results" -> {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new SarifParseException("SARIF \"results\" is not an array");
                    }
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode resultNode = objectMapper.readTree(parser);
                        pending.add(extractPending(resultNode, ordinal++));
                    }
                }
                default -> skipValue(parser);
            }
        }

        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new SarifParseException("SARIF run does not name its tool");
        }

        List<RawFinding> all = new ArrayList<>(pending.size());
        List<RawFinding> batch = new ArrayList<>(Math.min(batchSize, 1024));
        for (PendingResult candidate : pending) {
            RawFinding finding = candidate.resolve(tool, artifactUris);
            if (finding == null) {
                continue; // no rule id, or no location usable enough to track - dropped, as before.
            }
            all.add(finding);
            batch.add(finding);
            if (batch.size() >= batchSize) {
                batchConsumer.accept(List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            batchConsumer.accept(List.copyOf(batch));
        }

        return new AnalysisReport(tool.name(), tool.effectiveVersion(), all);
    }

    /** Reads the (small, bounded) {@code tool} object: driver name/version and each rule's default level. */
    private ToolInfo readTool(JsonParser parser) throws IOException {
        JsonNode toolNode = objectMapper.readTree(parser);
        JsonNode driver = toolNode.path("driver");
        String name = driver.path("name").asText(null);
        String semanticVersion = driver.path("semanticVersion").asText(null);
        String version = (semanticVersion != null && !semanticVersion.isBlank())
                ? semanticVersion
                : driver.path("version").asText("unknown");

        Map<String, String> defaultLevels = new LinkedHashMap<>();
        Map<Integer, String> ruleIdByIndex = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode rule : driver.path("rules")) {
            String ruleId = rule.path("id").asText(null);
            if (ruleId != null && !ruleId.isBlank()) {
                ruleIdByIndex.put(index, ruleId);
                JsonNode defaultConfiguration = rule.path("defaultConfiguration");
                String level = defaultConfiguration.path("level").asText(null);
                if (level != null && !level.isBlank()) {
                    defaultLevels.put(ruleId, level);
                }
            }
            index++;
        }
        return new ToolInfo(name, version, defaultLevels, ruleIdByIndex);
    }

    /** Reads {@code artifacts}, keeping only {@code location.uri} - the (potentially large) embedded
     *  {@code contents.text} is not read: §3.2's fingerprints need a line snippet, not a whole file. */
    private List<String> readArtifactUris(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw new SarifParseException("SARIF \"artifacts\" is not an array");
        }
        List<String> uris = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonNode artifact = objectMapper.readTree(parser);
            String uri = artifact.path("location").path("uri").asText(null);
            uris.add(uri == null ? "" : decodeUri(uri));
        }
        return uris;
    }

    /** Skips whatever value the parser is currently positioned on, object/array or scalar. */
    private void skipValue(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.START_OBJECT || parser.currentToken() == JsonToken.START_ARRAY) {
            parser.skipChildren();
        }
        // A scalar token is already fully consumed by the nextToken() that produced it.
    }

    private PendingResult extractPending(JsonNode result, int ordinal) {
        String ruleId = result.path("ruleId").asText(null);
        Integer ruleIndex = result.path("ruleIndex").isInt() ? result.path("ruleIndex").asInt() : null;
        String level = result.path("level").asText(null);
        String messageText = result.path("message").path("text").asText(null);
        Double securitySeverity = securitySeverity(result.path("properties"));

        List<CandidateLocation> locations = new ArrayList<>();
        for (JsonNode location : result.path("locations")) {
            JsonNode physical = location.path("physicalLocation");
            JsonNode artifactLocation = physical.path("artifactLocation");
            if (artifactLocation.isMissingNode()) {
                continue;
            }
            String uri = artifactLocation.path("uri").asText(null);
            Integer index = artifactLocation.path("index").isInt() ? artifactLocation.path("index").asInt() : null;
            JsonNode region = physical.path("region");
            int startLine = region.path("startLine").asInt(1);
            if (startLine < 1) {
                startLine = 1;
            }
            int endLine = region.path("endLine").asInt(startLine);
            int startColumn = region.path("startColumn").asInt(0);
            int endColumn = region.path("endColumn").asInt(0);
            String snippet = region.path("snippet").path("text").asText(null);
            String symbolPath = firstLogicalLocationName(location.path("logicalLocations"));

            locations.add(new CandidateLocation(
                    uri, index, startLine, endLine, startColumn, endColumn, snippet, symbolPath));
        }

        return new PendingResult(ordinal, ruleId, ruleIndex, level, messageText, securitySeverity, locations);
    }

    /** The first {@code logicalLocations[].fullyQualifiedName} on a location, or {@code null}. */
    private String firstLogicalLocationName(JsonNode logicalLocations) {
        for (JsonNode logicalLocation : logicalLocations) {
            String fullyQualifiedName = logicalLocation.path("fullyQualifiedName").asText(null);
            if (fullyQualifiedName != null && !fullyQualifiedName.isBlank()) {
                return fullyQualifiedName;
            }
        }
        return null;
    }

    /**
     * GitHub's code-scanning convention puts a CVSS-like score in a result property; it is the only
     * widely used way for a tool to say "this one is worse than the others" through SARIF's very
     * coarse four-level scale.
     */
    private Double securitySeverity(JsonNode properties) {
        JsonNode value = properties.path(SECURITY_SEVERITY);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.valueOf(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strips the {@code file:} scheme and percent-decoding that some analysers emit. */
    private static String decodeUri(String uri) {
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

    /** The driver identity plus enough of {@code rules[]} to resolve a result's severity and rule id. */
    private record ToolInfo(
            String name, String effectiveVersion, Map<String, String> defaultLevelsByRuleId, Map<Integer, String> ruleIdByIndex) {
    }

    /** One {@code locations[]} entry, kept exactly as reported - resolving {@code uri-or-index} waits for {@link ToolInfo}. */
    private record CandidateLocation(
            String uri,
            Integer artifactIndex,
            int startLine,
            int endLine,
            int startColumn,
            int endColumn,
            String snippet,
            String symbolPath) {
    }

    /**
     * A {@code results[]} entry with every field extracted but not yet resolved against its
     * sibling {@code tool}/{@code artifacts} data, which may not have streamed past yet at the
     * point this is built - see the class javadoc's "Field order and batching" section.
     */
    private record PendingResult(
            int ordinal,
            String ruleId,
            Integer ruleIndex,
            String level,
            String messageText,
            Double securitySeverity,
            List<CandidateLocation> locations) {

        /**
         * @return a finding, or {@code null} if the result has no usable rule id or no location
         *     that resolves to an actual file - both are cases where the pre-streaming reader also
         *     dropped the result, since there is nowhere to fingerprint it and nothing to show a
         *     reviewer
         */
        RawFinding resolve(ToolInfo tool, List<String> artifactUris) {
            String resolvedRuleId = ruleId != null && !ruleId.isBlank()
                    ? ruleId
                    : (ruleIndex == null ? null : tool.ruleIdByIndex().get(ruleIndex));
            if (resolvedRuleId == null || resolvedRuleId.isBlank()) {
                return null;
            }

            CandidateLocation location = resolveLocation(artifactUris);
            if (location == null) {
                return null;
            }

            String effectiveLevel = level != null ? level : tool.defaultLevelsByRuleId().get(resolvedRuleId);
            Severity severity = Severity.fromSarif(effectiveLevel, securitySeverity);
            String message = messageText != null && !messageText.isBlank() ? messageText : resolvedRuleId;

            var fingerprints = FingerprintFactory.compute(
                    resolvedRuleId, location.uri(), location.symbolPath(), location.snippet());

            return new RawFinding(
                    ordinal,
                    resolvedRuleId,
                    severity,
                    message,
                    location.uri(),
                    location.symbolPath(),
                    location.startLine(),
                    location.endLine(),
                    location.startColumn(),
                    location.endColumn(),
                    location.snippet(),
                    fingerprints);
        }

        /** The first candidate location whose URI is known, either directly or via an artifact index. */
        private CandidateLocation resolveLocation(List<String> artifactUris) {
            for (CandidateLocation candidate : locations) {
                String resolvedUri = resolveUri(candidate, artifactUris);
                if (resolvedUri != null && !resolvedUri.isBlank()) {
                    return new CandidateLocation(
                            resolvedUri,
                            candidate.artifactIndex(),
                            candidate.startLine(),
                            candidate.endLine(),
                            candidate.startColumn(),
                            candidate.endColumn(),
                            candidate.snippet(),
                            candidate.symbolPath());
                }
            }
            return null;
        }

        private String resolveUri(CandidateLocation candidate, List<String> artifactUris) {
            if (candidate.uri() != null && !candidate.uri().isBlank()) {
                return decodeUri(candidate.uri());
            }
            Integer index = candidate.artifactIndex();
            if (index != null && index >= 0 && index < artifactUris.size()) {
                String uri = artifactUris.get(index);
                return uri == null || uri.isBlank() ? null : uri;
            }
            return null;
        }
    }
}
