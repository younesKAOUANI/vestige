package dev.youneskaouani.vestige.matching.corpus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pairing the corpus author asserts as ground truth: {@code before} should match {@code after}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpectedMatch(String before, String after) {

    @JsonCreator
    public ExpectedMatch(
            @JsonProperty("before") String before, @JsonProperty("after") String after) {
        this.before = before;
        this.after = after;
    }
}
