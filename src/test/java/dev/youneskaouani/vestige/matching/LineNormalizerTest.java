package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LineNormalizerTest {

    @Test
    @DisplayName("§3.2's own worked example: literal changes hash identically once masked")
    void matchesTheSpecsWorkedExample() {
        String a = LineNormalizer.normalize("x = \"abc\" + 1");
        String b = LineNormalizer.normalize("x = \"def\" + 2");

        assertThat(a).isEqualTo(b).isEqualTo("x = § + §");
    }

    @Test
    @DisplayName("strips only leading and trailing whitespace, not interior whitespace")
    void stripsOnlyOuterWhitespace() {
        assertThat(LineNormalizer.normalize("    return 1;   ")).isEqualTo("return §;");
        assertThat(LineNormalizer.normalize("x  =  1")).isEqualTo("x  =  §");
    }

    @Test
    @DisplayName("masks single-quoted literals the same way as double-quoted ones")
    void masksSingleQuotedLiterals() {
        assertThat(LineNormalizer.normalize("char c = 'a';")).isEqualTo("char c = §;");
    }

    @Test
    @DisplayName("does not let an escaped quote end a literal early")
    void respectsBackslashEscapes() {
        assertThat(LineNormalizer.normalize("String s = \"a \\\"quoted\\\" word\";")).isEqualTo("String s = §;");
    }

    @Test
    @DisplayName("masks hexadecimal, decimal and floating-point literals")
    void masksVariousNumericForms() {
        assertThat(LineNormalizer.normalize("int mask = 0xFF;")).isEqualTo("int mask = §;");
        assertThat(LineNormalizer.normalize("double d = 3.14;")).isEqualTo("double d = §;");
        assertThat(LineNormalizer.normalize("long l = 42L;")).isEqualTo("long l = §;");
    }

    @Test
    @DisplayName("does not touch identifiers, keywords or operators")
    void leavesIdentifiersAlone() {
        assertThat(LineNormalizer.normalize("if (order.total > threshold) return order;"))
                .isEqualTo("if (order.total > threshold) return order;");
    }

    @Test
    @DisplayName("a renamed identifier is deliberately NOT tolerated by this rung alone")
    void aRenamedIdentifierChangesTheHash() {
        // This is the precise point documented on IssueMatcher and in ADR-001's consequences:
        // context_fp survives literal edits and whitespace, but an identifier rename is "an edit
        // to the flagged line itself" (§3.2's own "breaks on" column), so it is rung 3 (weak_fp
        // plus line proximity), not rung 2, that has to catch it.
        assertThat(LineNormalizer.normalize("return order.getId();"))
                .isNotEqualTo(LineNormalizer.normalize("return o.getId();"));
    }

    @Test
    @DisplayName("an empty or blank line normalises to empty, not to a meaningless hash input")
    void handlesBlankLines() {
        assertThat(LineNormalizer.normalize("")).isEmpty();
        assertThat(LineNormalizer.normalize("   ")).isEmpty();
        assertThat(LineNormalizer.normalize(null)).isNull();
    }
}
