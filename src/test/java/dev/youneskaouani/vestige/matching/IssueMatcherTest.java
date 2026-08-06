package dev.youneskaouani.vestige.matching;

import static dev.youneskaouani.vestige.matching.MatchingFixtures.finding;
import static dev.youneskaouani.vestige.matching.MatchingFixtures.snapshot;
import static dev.youneskaouani.vestige.matching.MatchingFixtures.trackedFrom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IssueMatcherTest {

    private static final String RULE = "java:S2259";
    private static final String PATH = "src/main/java/Sample.java";

    private final IssueMatcher matcher = new IssueMatcher();

    private static List<String> file() {
        return List.of(
                "package demo;",
                "",
                "public class Sample {",
                "",
                "    void handle(String input) {",
                "        String value = lookup(input);",
                "        System.out.println(value.length());",
                "    }",
                "}");
    }

    private static List<String> fileWithHeader(int extraLines) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < extraLines; i++) {
            lines.add("// header " + i);
        }
        lines.addAll(file());
        return lines;
    }

    @Test
    @DisplayName("the first analysis turns every finding into a new issue")
    void firstRunHasNoMatches() {
        SourceSnapshot snapshot = snapshot(PATH, file());
        MatchResult result = matcher.match(
                MatchRequest.firstRun(List.of(finding("f1", RULE, PATH, 7, snapshot))));

        assertThat(result.matches()).isEmpty();
        assertThat(result.newFindings()).extracting(CandidateFinding::id).containsExactly("f1");
        assertThat(result.disappearedIssues()).isEmpty();
    }

    @Nested
    @DisplayName("pass 1 - exact fingerprint")
    class ExactFingerprint {

        @Test
        @DisplayName("matches on the analyser's fingerprint even when path and line both changed")
        void winsOverEverything() {
            SourceSnapshot before = snapshot(PATH, file());
            CandidateFinding original = finding("f0", RULE, PATH, 7, before, "abc123");
            TrackedIssue issue = trackedFrom("i1", original);

            SourceSnapshot after = snapshot("src/main/java/Renamed.java", fileWithHeader(40));
            CandidateFinding moved =
                    finding("f1", RULE, "src/main/java/Renamed.java", 47, after, "abc123");

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(moved), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.issue().id()).isEqualTo("i1");
                assertThat(match.finding().id()).isEqualTo("f1");
                assertThat(match.strategy()).isEqualTo(MatchStrategy.EXACT_FINGERPRINT);
                assertThat(match.confidence()).isEqualTo(1.0);
            });
        }

        @Test
        @DisplayName("does not match two different rules that share a fingerprint value")
        void keepsRulesApart() {
            SourceSnapshot snapshot = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, snapshot, "shared"));
            CandidateFinding other = finding("f1", "java:S1481", PATH, 7, snapshot, "shared");

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(other), DiffModel.empty()));

            assertThat(result.matches()).isEmpty();
            assertThat(result.disappearedIssues()).extracting(TrackedIssue::id).containsExactly("i1");
            assertThat(result.newFindings()).extracting(CandidateFinding::id).containsExactly("f1");
        }
    }

    @Nested
    @DisplayName("pass 2 - diff-aware line remapping")
    class DiffRemapping {

        @Test
        @DisplayName("follows a finding that the diff pushed down the file")
        void followsInsertedLines() {
            SourceSnapshot before = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, before));

            List<String> afterLines = fileWithHeader(5);
            SourceSnapshot after = snapshot(PATH, afterLines);
            CandidateFinding moved = finding("f1", RULE, PATH, 12, after);
            DiffModel diff = UnifiedDiffParser.parse(
                    TestUnifiedDiff.between(PATH, file(), PATH, afterLines));

            MatchResult result = matcher.match(new MatchRequest(List.of(issue), List.of(moved), diff));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.issue().id()).isEqualTo("i1");
                assertThat(match.strategy()).isEqualTo(MatchStrategy.DIFF_REMAPPED_LINE);
                assertThat(match.confidence()).isEqualTo(0.9);
            });
        }

        @Test
        @DisplayName("follows a renamed file through the diff's rename header")
        void followsRenames() {
            String renamed = "src/main/java/Renamed.java";
            SourceSnapshot before = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, before));

            SourceSnapshot after = snapshot(renamed, file());
            CandidateFinding sameLine = finding("f1", RULE, renamed, 7, after);
            DiffModel diff =
                    UnifiedDiffParser.parse(TestUnifiedDiff.between(PATH, file(), renamed, file()));

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(sameLine), diff));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match ->
                    assertThat(match.strategy()).isEqualTo(MatchStrategy.DIFF_REMAPPED_LINE));
        }

        @Test
        @DisplayName("without a diff it still matches an untouched file at the same line")
        void degradesToIdentityWithoutDiff() {
            SourceSnapshot snapshot = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, snapshot));
            CandidateFinding same = finding("f1", RULE, PATH, 7, snapshot);

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(same), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match ->
                    assertThat(match.strategy()).isEqualTo(MatchStrategy.DIFF_REMAPPED_LINE));
        }

        @Test
        @DisplayName("refuses a positional coincidence that the file contents contradict")
        void refusesContradictedPosition() {
            List<String> beforeLines = List.of(
                    "class A {",
                    "    void alpha() {",
                    "        String a = null;",
                    "        System.out.println(a.length());",
                    "    }",
                    "    void beta() {",
                    "        String b = null;",
                    "        System.out.println(b.length());",
                    "    }",
                    "}");
            // The two methods swap places: line 4 now holds a completely different violation.
            List<String> afterLines = List.of(
                    "class A {",
                    "    void beta() {",
                    "        String b = null;",
                    "        System.out.println(b.length());",
                    "    }",
                    "    void alpha() {",
                    "        String a = null;",
                    "        System.out.println(a.length());",
                    "    }",
                    "}");

            SourceSnapshot before = snapshot(PATH, beforeLines);
            SourceSnapshot after = snapshot(PATH, afterLines);
            TrackedIssue alpha = trackedFrom("i-alpha", finding("f0", RULE, PATH, 4, before));
            TrackedIssue beta = trackedFrom("i-beta", finding("f1", RULE, PATH, 8, before));
            CandidateFinding betaNow = finding("g0", RULE, PATH, 4, after);
            CandidateFinding alphaNow = finding("g1", RULE, PATH, 8, after);

            MatchResult result = matcher.match(
                    new MatchRequest(List.of(alpha, beta), List.of(betaNow, alphaNow), DiffModel.empty()));

            assertThat(result.matches())
                    .extracting(m -> m.issue().id() + "->" + m.finding().id())
                    .containsExactlyInAnyOrder("i-alpha->g1", "i-beta->g0");
            assertThat(result.matches()).allSatisfy(match ->
                    assertThat(match.strategy()).isEqualTo(MatchStrategy.STRUCTURAL_HASH));
        }
    }

    @Nested
    @DisplayName("pass 3 - structural hash")
    class StructuralHash {

        @Test
        @DisplayName("survives a line shift that no diff described")
        void survivesUndescribedShift() {
            SourceSnapshot before = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, before));

            SourceSnapshot after = snapshot(PATH, fileWithHeader(20));
            CandidateFinding moved = finding("f1", RULE, PATH, 27, after);

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(moved), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.strategy()).isEqualTo(MatchStrategy.STRUCTURAL_HASH);
                assertThat(match.confidence()).isEqualTo(0.8);
            });
        }

        @Test
        @DisplayName("survives the whole file being reindented")
        void survivesReindent() {
            List<String> reindented = file().stream().map(line -> line.replace("    ", "\t")).toList();
            SourceSnapshot before = snapshot(PATH, file());
            SourceSnapshot after = snapshot(PATH, reindented);

            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, before));
            CandidateFinding sameLine = finding("f1", RULE, PATH, 7, after);

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(sameLine), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match ->
                    assertThat(match.strategy()).isIn(
                            MatchStrategy.DIFF_REMAPPED_LINE, MatchStrategy.STRUCTURAL_HASH));
        }
    }

    @Nested
    @DisplayName("pass 4 - line content hash")
    class LineContentHash {

        @Test
        @DisplayName("survives a rename that no diff described, because the path is not hashed")
        void survivesUndescribedRename() {
            String renamed = "src/main/java/Renamed.java";
            SourceSnapshot before = snapshot(PATH, file());
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 7, before));

            SourceSnapshot after = snapshot(renamed, fileWithHeader(3));
            CandidateFinding moved = finding("f1", RULE, renamed, 10, after);

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(moved), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.strategy()).isEqualTo(MatchStrategy.LINE_CONTENT_HASH);
                assertThat(match.confidence()).isEqualTo(0.6);
            });
        }
    }

    @Nested
    @DisplayName("pass 5 - positional fallback")
    class PositionalFallback {

        @Test
        @DisplayName("matches a small drift when no content and no diff are available")
        void matchesWithinWindow() {
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 40, SourceSnapshot.empty()));
            CandidateFinding drifted = finding("f1", RULE, PATH, 44, SourceSnapshot.empty());

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(drifted), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.strategy()).isEqualTo(MatchStrategy.POSITIONAL_FALLBACK);
                assertThat(match.confidence()).isEqualTo(0.3);
            });
        }

        @Test
        @DisplayName("refuses a drift larger than the window")
        void refusesBeyondWindow() {
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 40, SourceSnapshot.empty()));
            CandidateFinding drifted = finding("f1", RULE, PATH, 60, SourceSnapshot.empty());

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(drifted), DiffModel.empty()));

            assertThat(result.matches()).isEmpty();
            assertThat(result.disappearedIssues()).extracting(TrackedIssue::id).containsExactly("i1");
            assertThat(result.newFindings()).extracting(CandidateFinding::id).containsExactly("f1");
        }

        @Test
        @DisplayName("claims the nearest candidate first when several are in range")
        void prefersNearest() {
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 40, SourceSnapshot.empty()));
            CandidateFinding far = finding("f-far", RULE, PATH, 47, SourceSnapshot.empty());
            CandidateFinding near = finding("f-near", RULE, PATH, 42, SourceSnapshot.empty());

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(issue), List.of(far, near), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match ->
                    assertThat(match.finding().id()).isEqualTo("f-near"));
            assertThat(result.newFindings()).extracting(CandidateFinding::id).containsExactly("f-far");
        }

        @Test
        @DisplayName("can be switched off with a zero drift window")
        void honoursConfiguredWindow() {
            IssueMatcher strict = new IssueMatcher(new MatchingOptions(0));
            TrackedIssue issue = trackedFrom("i1", finding("f0", RULE, PATH, 40, SourceSnapshot.empty()));
            CandidateFinding drifted = finding("f1", RULE, PATH, 41, SourceSnapshot.empty());

            MatchResult result =
                    strict.match(new MatchRequest(List.of(issue), List.of(drifted), DiffModel.empty()));

            assertThat(result.matches()).isEmpty();
        }
    }

    @Nested
    @DisplayName("pipeline behaviour")
    class Pipeline {

        @Test
        @DisplayName("a claimed issue is invisible to later passes")
        void laterPassesSeeOnlyWhatIsLeft() {
            SourceSnapshot snapshot = snapshot(PATH, file());
            CandidateFinding original = finding("f0", RULE, PATH, 7, snapshot, "fp-1");
            TrackedIssue tracked = trackedFrom("i1", original);
            TrackedIssue decoy = new TrackedIssue(
                    "i2",
                    RULE,
                    tracked.severity(),
                    IssueStatus.OPEN,
                    SourceLocation.ofLine(PATH, 7),
                    tracked.fingerprints());

            CandidateFinding incoming = finding("f1", RULE, PATH, 7, snapshot, "fp-1");

            MatchResult result = matcher.match(
                    new MatchRequest(List.of(tracked, decoy), List.of(incoming), DiffModel.empty()));

            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().get(0)).satisfies(match -> {
                assertThat(match.issue().id()).isEqualTo("i1");
                assertThat(match.strategy()).isEqualTo(MatchStrategy.EXACT_FINGERPRINT);
            });
            assertThat(result.disappearedIssues()).extracting(TrackedIssue::id).containsExactly("i2");
        }

        @Test
        @DisplayName("pairs as many of an ambiguous bucket as it can and leaves the surplus new")
        void handlesAmbiguityDeterministically() {
            SourceSnapshot snapshot = snapshot(PATH, file());
            CandidateFinding template = finding("f0", RULE, PATH, 7, snapshot);
            TrackedIssue first = trackedFrom("i1", template);
            TrackedIssue second = trackedFrom("i2", template);

            List<CandidateFinding> incoming = List.of(
                    finding("g1", RULE, PATH, 7, snapshot),
                    finding("g2", RULE, PATH, 7, snapshot),
                    finding("g3", RULE, PATH, 7, snapshot));

            MatchResult result =
                    matcher.match(new MatchRequest(List.of(first, second), incoming, DiffModel.empty()));

            assertThat(result.matches()).hasSize(2);
            assertThat(result.newFindings()).hasSize(1);
            assertThat(result.matches())
                    .extracting(m -> m.issue().id() + "->" + m.finding().id())
                    .containsExactly("i1->g1", "i2->g2");
        }

        @Test
        @DisplayName("produces the same result whatever order the inputs arrive in")
        void isOrderIndependent() {
            SourceSnapshot before = snapshot(PATH, file());
            SourceSnapshot after = snapshot(PATH, fileWithHeader(4));

            List<TrackedIssue> issues = new ArrayList<>(List.of(
                    trackedFrom("i1", finding("f0", RULE, PATH, 7, before)),
                    trackedFrom("i2", finding("f1", "java:S1481", PATH, 6, before)),
                    trackedFrom("i3", finding("f2", RULE, "other/File.java", 3, SourceSnapshot.empty()))));
            List<CandidateFinding> findings = new ArrayList<>(List.of(
                    finding("g1", RULE, PATH, 11, after),
                    finding("g2", "java:S1481", PATH, 10, after),
                    finding("g3", "java:S106", PATH, 11, after)));

            List<String> reference = fingerprintOf(matcher.match(
                    new MatchRequest(issues, findings, DiffModel.empty())));

            Random random = new Random(99);
            for (int i = 0; i < 25; i++) {
                Collections.shuffle(issues, random);
                Collections.shuffle(findings, random);
                assertThat(fingerprintOf(matcher.match(
                                new MatchRequest(issues, findings, DiffModel.empty()))))
                        .isEqualTo(reference);
            }
        }

        @Test
        @DisplayName("counts how many matches each strategy produced")
        void reportsStrategyHistogram() {
            SourceSnapshot before = snapshot(PATH, file());
            SourceSnapshot after = snapshot(PATH, fileWithHeader(15));

            List<TrackedIssue> issues = List.of(
                    trackedFrom("i1", finding("f0", RULE, PATH, 7, before, "fp")),
                    trackedFrom("i2", finding("f1", "java:S1481", PATH, 6, before)));
            List<CandidateFinding> findings = List.of(
                    finding("g1", RULE, PATH, 22, after, "fp"),
                    finding("g2", "java:S1481", PATH, 21, after));

            MatchResult result =
                    matcher.match(new MatchRequest(issues, findings, DiffModel.empty()));

            assertThat(result.strategyHistogram())
                    .containsEntry(MatchStrategy.EXACT_FINGERPRINT, 1L)
                    .containsEntry(MatchStrategy.STRUCTURAL_HASH, 1L);
            assertThat(result.byIssueId()).containsOnlyKeys("i1", "i2");
        }

        @Test
        @DisplayName("rejects duplicate identifiers rather than silently mismatching")
        void rejectsDuplicateIds() {
            SourceSnapshot snapshot = snapshot(PATH, file());
            CandidateFinding one = finding("dup", RULE, PATH, 7, snapshot);
            CandidateFinding two = finding("dup", RULE, PATH, 6, snapshot);

            assertThatThrownBy(() -> new MatchRequest(List.of(), List.of(one, two), DiffModel.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate id");
        }
    }

    private static List<String> fingerprintOf(MatchResult result) {
        List<String> lines = new ArrayList<>();
        result.matches().stream()
                .map(m -> "M " + m.issue().id() + " " + m.finding().id() + " " + m.strategy())
                .sorted()
                .forEach(lines::add);
        result.newFindings().stream().map(f -> "N " + f.id()).sorted().forEach(lines::add);
        result.disappearedIssues().stream().map(i -> "R " + i.id()).sorted().forEach(lines::add);
        return lines;
    }
}
