package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathNormalizerTest {

    @Test
    @DisplayName("leaves an already-clean relative path untouched")
    void leavesACleanPathUntouched() {
        assertThat(PathNormalizer.normalize("src/main/java/com/acme/PaymentService.java"))
                .isEqualTo("src/main/java/com/acme/PaymentService.java");
    }

    @Test
    @DisplayName("converts backslashes to forward slashes")
    void convertsBackslashes() {
        assertThat(PathNormalizer.normalize("src\\main\\java\\PaymentService.java"))
                .isEqualTo("src/main/java/PaymentService.java");
    }

    @Test
    @DisplayName("strips a leading ./ and a leading /")
    void stripsLeadingMarkers() {
        assertThat(PathNormalizer.normalize("./src/Main.java")).isEqualTo("src/Main.java");
        assertThat(PathNormalizer.normalize("/src/Main.java")).isEqualTo("src/Main.java");
    }

    @Test
    @DisplayName("collapses doubled slashes and interior ./ segments")
    void collapsesRedundantSegments() {
        assertThat(PathNormalizer.normalize("src//main/./java//Main.java")).isEqualTo("src/main/java/Main.java");
    }

    @Test
    @DisplayName("strips a trailing slash")
    void stripsTrailingSlash() {
        assertThat(PathNormalizer.normalize("src/main/")).isEqualTo("src/main");
    }

    @Test
    @DisplayName("preserves case: file systems that matter here are case-sensitive")
    void preservesCase() {
        assertThat(PathNormalizer.normalize("src/Main.java")).isNotEqualTo(PathNormalizer.normalize("src/main.java"));
    }

    @Test
    @DisplayName("treats null and blank as the empty path rather than throwing")
    void handlesNullAndBlank() {
        assertThat(PathNormalizer.normalize(null)).isEmpty();
        assertThat(PathNormalizer.normalize("")).isEmpty();
        assertThat(PathNormalizer.normalize("   ")).isEmpty();
    }
}
