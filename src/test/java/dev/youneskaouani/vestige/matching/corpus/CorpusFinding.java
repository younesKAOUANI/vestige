package dev.youneskaouani.vestige.matching.corpus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One finding on either side of a {@link CorpusCase}, exactly as matcher-corpus/*.json spells it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorpusFinding(
        String id, String ruleId, String filePath, String symbolPath, int line, String snippet) {

    // Explicit @JsonCreator/@JsonProperty rather than relying on -parameters + Jackson's implicit
    // record support: this fixture set is the harness that gates the build, so its own parsing
    // takes no dependency on compiler-flag or Jackson-version behaviour it does not have to.
    @JsonCreator
    public CorpusFinding(
            @JsonProperty("id") String id,
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("symbolPath") String symbolPath,
            @JsonProperty("line") int line,
            @JsonProperty("snippet") String snippet) {
        this.id = id;
        this.ruleId = ruleId;
        this.filePath = filePath;
        this.symbolPath = symbolPath;
        this.line = line;
        this.snippet = snippet;
    }
}
