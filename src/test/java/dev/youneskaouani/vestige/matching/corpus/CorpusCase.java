package dev.youneskaouani.vestige.matching.corpus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One hand-authored before/after fixture: a real refactor shape, the findings on each side, and the
 * matching a human asserts is correct. See {@code matcher-corpus/generate_cases.py} for how these
 * are produced and {@code matcher-corpus/README.md} for the field-by-field format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorpusCase(
        String id,
        String description,
        String refactorShape,
        Map<String, String> renames,
        List<CorpusFinding> before,
        List<CorpusFinding> after,
        List<ExpectedMatch> expectedMatches) {

    @JsonCreator
    public CorpusCase(
            @JsonProperty("id") String id,
            @JsonProperty("description") String description,
            @JsonProperty("refactorShape") String refactorShape,
            @JsonProperty("renames") Map<String, String> renames,
            @JsonProperty("before") List<CorpusFinding> before,
            @JsonProperty("after") List<CorpusFinding> after,
            @JsonProperty("expectedMatches") List<ExpectedMatch> expectedMatches) {
        this.id = id;
        this.description = description;
        this.refactorShape = refactorShape;
        this.renames = renames == null ? Map.of() : Map.copyOf(renames);
        this.before = before == null ? List.of() : List.copyOf(before);
        this.after = after == null ? List.of() : List.copyOf(after);
        this.expectedMatches = expectedMatches == null ? List.of() : List.copyOf(expectedMatches);
    }

    /**
     * Fails fast on a fixture-authoring mistake: every id an expectation names must actually exist.
     */
    public void validate() {
        var beforeIds =
                before.stream().map(CorpusFinding::id).collect(java.util.stream.Collectors.toSet());
        var afterIds =
                after.stream().map(CorpusFinding::id).collect(java.util.stream.Collectors.toSet());
        if (beforeIds.size() != before.size()) {
            throw new IllegalStateException(id + ": duplicate before-finding ids");
        }
        if (afterIds.size() != after.size()) {
            throw new IllegalStateException(id + ": duplicate after-finding ids");
        }
        for (ExpectedMatch expected : expectedMatches) {
            if (!beforeIds.contains(expected.before())) {
                throw new IllegalStateException(
                        id + ": expectedMatches references unknown before id " + expected.before());
            }
            if (!afterIds.contains(expected.after())) {
                throw new IllegalStateException(
                        id + ": expectedMatches references unknown after id " + expected.after());
            }
        }
    }
}
