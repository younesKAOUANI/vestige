package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FingerprintCalculatorTest {

    private static final String RULE = "java:S2259";
    private static final String PATH = "src/Sample.java";

    private static final List<String> FILE = List.of(
            "package demo;",
            "",
            "public class Sample {",
            "",
            "    void handle(String input) {",
            "        String value = lookup(input);",
            "        System.out.println(value.length());",
            "    }",
            "",
            "    void other() {",
            "        System.out.println(\"other\");",
            "    }",
            "}");

    private static SourceSnapshot snapshotOf(List<String> lines) {
        return SourceSnapshot.ofFileContents(Map.of(PATH, String.join("\n", lines)));
    }

    private static Fingerprints fingerprintsAt(List<String> lines, int line) {
        return new FingerprintCalculator(snapshotOf(lines))
                .compute(RULE, SourceLocation.ofLine(PATH, line), null);
    }

    @Nested
    @DisplayName("text normalisation")
    class Normalisation {

        @Test
        @DisplayName("collapses whitespace runs and trims the ends")
        void collapsesWhitespace() {
            assertThat(TextNormalizer.collapseWhitespace("  a\t\tb   c  ")).isEqualTo("a b c");
            assertThat(TextNormalizer.collapseWhitespace("   ")).isEmpty();
            assertThat(TextNormalizer.collapseWhitespace(null)).isEmpty();
        }

        @Test
        @DisplayName("drops blank lines when normalising a block")
        void dropsBlankLines() {
            assertThat(TextNormalizer.normaliseBlock(List.of("  a  ", "", "   ", "\tb")))
                    .isEqualTo("a\nb");
        }

        @Test
        @DisplayName("canonicalises paths without changing their case")
        void canonicalisesPaths() {
            assertThat(TextNormalizer.normalisePath("./src//main/Foo.java")).isEqualTo("src/main/Foo.java");
            assertThat(TextNormalizer.normalisePath("\\src\\main\\Foo.java")).isEqualTo("src/main/Foo.java");
            assertThat(TextNormalizer.normalisePath("/src/main/Foo.java")).isEqualTo("src/main/Foo.java");
            assertThat(TextNormalizer.normalisePath("src/main/FOO.java")).isEqualTo("src/main/FOO.java");
        }
    }

    @Nested
    @DisplayName("enclosing block extraction")
    class BlockExtraction {

        @Test
        @DisplayName("returns the innermost brace-delimited block containing the line")
        void findsInnermostBlock() {
            assertThat(EnclosingBlockExtractor.extract(FILE, 7))
                    .containsExactly(
                            "    void handle(String input) {",
                            "        String value = lookup(input);",
                            "        System.out.println(value.length());",
                            "    }");
        }

        @Test
        @DisplayName("falls back to a symmetric window when there is no brace to anchor to")
        void fallsBackToWindow() {
            List<String> plain = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot");
            assertThat(EnclosingBlockExtractor.extract(plain, 4))
                    .containsExactly("alpha", "bravo", "charlie", "delta", "echo", "foxtrot");
            assertThat(EnclosingBlockExtractor.extract(plain, 1))
                    .containsExactly("alpha", "bravo", "charlie", "delta");
        }

        @Test
        @DisplayName("clamps a line number outside the file rather than failing")
        void clampsOutOfRange() {
            assertThat(EnclosingBlockExtractor.extract(FILE, 9_999)).isNotEmpty();
            assertThat(EnclosingBlockExtractor.extract(List.of(), 3)).isEmpty();
        }
    }

    @Nested
    @DisplayName("fingerprints")
    class Fingerprinting {

        @Test
        @DisplayName("passes the analyser's fingerprint through untouched")
        void keepsExactFingerprint() {
            Fingerprints fingerprints = new FingerprintCalculator(snapshotOf(FILE))
                    .compute(RULE, SourceLocation.ofLine(PATH, 7), "tool-supplied");
            assertThat(fingerprints.exact()).isEqualTo("tool-supplied");
        }

        @Test
        @DisplayName("yields only the analyser's fingerprint when no source is available")
        void degradesWithoutSource() {
            Fingerprints fingerprints = new FingerprintCalculator(SourceSnapshot.empty())
                    .compute(RULE, SourceLocation.ofLine(PATH, 7), "tool-supplied");
            assertThat(fingerprints.exact()).isEqualTo("tool-supplied");
            assertThat(fingerprints.structural()).isNull();
            assertThat(fingerprints.lineContent()).isNull();
        }

        @Test
        @DisplayName("is unchanged when lines are inserted above the block")
        void survivesLineShift() {
            List<String> shifted = new java.util.ArrayList<>(List.of("// added", "// added"));
            shifted.addAll(FILE);

            assertThat(fingerprintsAt(shifted, 9).structural())
                    .isEqualTo(fingerprintsAt(FILE, 7).structural());
        }

        @Test
        @DisplayName("is unchanged when the file is reindented")
        void survivesReindent() {
            List<String> reindented = FILE.stream().map(line -> line.replace("    ", "\t")).toList();

            assertThat(fingerprintsAt(reindented, 7).structural())
                    .isEqualTo(fingerprintsAt(FILE, 7).structural());
            assertThat(fingerprintsAt(reindented, 7).lineContent())
                    .isEqualTo(fingerprintsAt(FILE, 7).lineContent());
        }

        @Test
        @DisplayName("changes when the enclosing block is edited")
        void reactsToBlockEdits() {
            List<String> edited = new java.util.ArrayList<>(FILE);
            edited.set(5, "        String value = lookup(input.trim());");

            assertThat(fingerprintsAt(edited, 7).structural())
                    .isNotEqualTo(fingerprintsAt(FILE, 7).structural());
            assertThat(fingerprintsAt(edited, 7).lineContent())
                    .isEqualTo(fingerprintsAt(FILE, 7).lineContent());
        }

        @Test
        @DisplayName("puts the path in the structural hash but not in the line hash")
        void treatsPathDifferentlyPerHash() {
            SourceLocation here = SourceLocation.ofLine(PATH, 7);
            SourceLocation elsewhere = SourceLocation.ofLine("src/Renamed.java", 7);
            SourceSnapshot snapshot = SourceSnapshot.ofFileContents(Map.of(
                    PATH, String.join("\n", FILE),
                    "src/Renamed.java", String.join("\n", FILE)));
            FingerprintCalculator calculator = new FingerprintCalculator(snapshot);

            assertThat(calculator.compute(RULE, elsewhere, null).structural())
                    .isNotEqualTo(calculator.compute(RULE, here, null).structural());
            assertThat(calculator.compute(RULE, elsewhere, null).lineContent())
                    .isEqualTo(calculator.compute(RULE, here, null).lineContent());
        }

        @Test
        @DisplayName("keeps different rules on the same line apart")
        void separatesRules() {
            FingerprintCalculator calculator = new FingerprintCalculator(snapshotOf(FILE));
            SourceLocation location = SourceLocation.ofLine(PATH, 7);

            assertThat(calculator.compute("java:S1481", location, null).lineContent())
                    .isNotEqualTo(calculator.compute(RULE, location, null).lineContent());
            assertThat(calculator.compute("java:S1481", location, null).structural())
                    .isNotEqualTo(calculator.compute(RULE, location, null).structural());
        }

        @Test
        @DisplayName("keeps two identical statements in different methods apart")
        void separatesIdenticalLinesInDifferentBlocks() {
            List<String> duplicated = List.of(
                    "class A {",
                    "    void first() {",
                    "        int x = 1;",
                    "        return;",
                    "    }",
                    "    void second() {",
                    "        int y = 2;",
                    "        return;",
                    "    }",
                    "}");

            assertThat(fingerprintsAt(duplicated, 4).structural())
                    .isNotEqualTo(fingerprintsAt(duplicated, 8).structural());
            // The line-content hash genuinely cannot tell them apart - that is why it is pass 4.
            assertThat(fingerprintsAt(duplicated, 4).lineContent())
                    .isEqualTo(fingerprintsAt(duplicated, 8).lineContent());
        }
    }
}
