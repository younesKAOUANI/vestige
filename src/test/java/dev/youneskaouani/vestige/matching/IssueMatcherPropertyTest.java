package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property-based-style tests for the matcher.
 *
 * <p>Rather than assert a handful of hand-picked outcomes, these generate synthetic source files,
 * apply a randomised edit of a known kind, and check the properties that must hold for
 * <em>every</em> such edit:
 *
 * <ol>
 *   <li><b>Nothing is lost.</b> Every violation that still exists after the edit is matched to the
 *       issue that was tracking it before — its identity, and therefore its triage history, is
 *       preserved.
 *   <li><b>Nothing is duplicated.</b> No issue is matched twice, no finding is matched twice, and a
 *       violation that survived does not also appear as a brand-new issue.
 *   <li><b>Nothing is invented.</b> A violation that the edit removed is resolved rather than
 *       silently re-pointed at some other finding; a violation the edit introduced is new rather
 *       than silently absorbed into an existing issue.
 *   <li><b>The result is order-independent.</b> Shuffling both input lists changes nothing.
 * </ol>
 *
 * <p>Each edit kind is run both with and without a diff, because the whole point of the multi-pass
 * design is that these properties survive the loss of the diff — the passes below it just have to
 * work harder.
 */
class IssueMatcherPropertyTest {

    private static final String RULE = "java:S2259";
    private static final int SEEDS_PER_EDIT = 12;

    /** The kinds of edit the generator knows how to make. */
    private enum Edit {
        INSERT_LINES_ABOVE,
        DELETE_UNRELATED_LINES,
        MOVE_BLOCK,
        RENAME_FILE,
        REINDENT,
        FIX_ONE_VIOLATION,
        INTRODUCE_ONE_VIOLATION,
        COMBINED
    }

    private static Stream<Arguments> scenarios() {
        List<Arguments> cases = new ArrayList<>();
        for (Edit edit : Edit.values()) {
            for (int seed = 1; seed <= SEEDS_PER_EDIT; seed++) {
                cases.add(Arguments.of(edit, (long) seed, true));
                cases.add(Arguments.of(edit, (long) seed, false));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, seed {1}, diff supplied: {2}")
    @MethodSource("scenarios")
    @DisplayName("neither loses nor duplicates issues across a synthetic edit")
    void neitherLosesNorDuplicatesIssues(Edit edit, long seed, boolean supplyDiff) {
        Random random = new Random(seed * 31 + edit.ordinal());
        Version before = generate(random);
        Version after = apply(edit, before, random);

        List<TrackedIssue> issues = new ArrayList<>();
        Map<Integer, String> issueIdByViolation = new LinkedHashMap<>();
        for (int violation : before.violations()) {
            CandidateFinding sighting = before.findingFor(violation, "prev-" + violation);
            String issueId = "issue-" + violation;
            issues.add(MatchingFixtures.trackedFrom(issueId, sighting));
            issueIdByViolation.put(violation, issueId);
        }

        List<CandidateFinding> findings = new ArrayList<>();
        Map<Integer, String> findingIdByViolation = new LinkedHashMap<>();
        for (int violation : after.violations()) {
            String findingId = "finding-" + violation;
            findings.add(after.findingFor(violation, findingId));
            findingIdByViolation.put(violation, findingId);
        }

        DiffModel diff = supplyDiff
                ? UnifiedDiffParser.parse(
                        TestUnifiedDiff.between(before.path(), before.lines(), after.path(), after.lines()))
                : DiffModel.empty();

        MatchResult result = new IssueMatcher().match(new MatchRequest(issues, findings, diff));

        Set<Integer> survived = new LinkedHashSet<>(before.violations());
        survived.retainAll(after.violations());

        // Property 1 and 2: every surviving violation keeps exactly its own issue.
        Map<String, String> matchedFindingByIssue = new LinkedHashMap<>();
        result.matches().forEach(m -> matchedFindingByIssue.put(m.issue().id(), m.finding().id()));
        for (int violation : survived) {
            assertThat(matchedFindingByIssue)
                    .as("violation %d after %s (diff supplied: %s)", violation, edit, supplyDiff)
                    .containsEntry(issueIdByViolation.get(violation), findingIdByViolation.get(violation));
        }

        // Property 3: what disappeared is resolved, what appeared is new.
        Set<Integer> removed = new LinkedHashSet<>(before.violations());
        removed.removeAll(after.violations());
        assertThat(result.disappearedIssues())
                .as("resolved issues after %s", edit)
                .extracting(TrackedIssue::id)
                .containsExactlyInAnyOrderElementsOf(removed.stream().map(issueIdByViolation::get).toList());

        Set<Integer> added = new LinkedHashSet<>(after.violations());
        added.removeAll(before.violations());
        assertThat(result.newFindings())
                .as("new findings after %s", edit)
                .extracting(CandidateFinding::id)
                .containsExactlyInAnyOrderElementsOf(added.stream().map(findingIdByViolation::get).toList());

        // Property 2 again, stated over the whole partition rather than per violation.
        assertThat(result.findingCount()).isEqualTo(findings.size());
        assertThat(result.previousIssueCount()).isEqualTo(issues.size());
        assertThat(result.matches()).extracting(m -> m.issue().id()).doesNotHaveDuplicates();
        assertThat(result.matches()).extracting(m -> m.finding().id()).doesNotHaveDuplicates();

        // Property 4: order independence.
        List<TrackedIssue> shuffledIssues = new ArrayList<>(issues);
        List<CandidateFinding> shuffledFindings = new ArrayList<>(findings);
        Collections.shuffle(shuffledIssues, random);
        Collections.shuffle(shuffledFindings, random);
        MatchResult reshuffled =
                new IssueMatcher().match(new MatchRequest(shuffledIssues, shuffledFindings, diff));
        assertThat(summarise(reshuffled)).isEqualTo(summarise(result));
    }

    private static List<String> summarise(MatchResult result) {
        List<String> summary = new ArrayList<>();
        result.matches().stream()
                .map(m -> "M " + m.issue().id() + " " + m.finding().id() + " " + m.strategy())
                .sorted()
                .forEach(summary::add);
        result.newFindings().stream().map(f -> "N " + f.id()).sorted().forEach(summary::add);
        result.disappearedIssues().stream().map(i -> "R " + i.id()).sorted().forEach(summary::add);
        return summary;
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic project generation
    // ---------------------------------------------------------------------------------------

    /**
     * One version of a synthetic source file.
     *
     * @param violations the identifiers of the violations present, each of which appears on exactly
     *     one line whose text mentions it
     */
    private record Version(String path, List<String> lines, List<Integer> violations) {

        SourceSnapshot snapshot() {
            return SourceSnapshot.ofFileContents(Map.of(path, String.join("\n", lines)));
        }

        int lineOf(int violation) {
            String marker = marker(violation);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(marker)) {
                    return i + 1;
                }
            }
            throw new IllegalStateException("Violation " + violation + " is not in this version");
        }

        CandidateFinding findingFor(int violation, String id) {
            return MatchingFixtures.finding(id, RULE, path, lineOf(violation), snapshot());
        }
    }

    /** The text that uniquely identifies a violation's line. */
    private static String marker(int violation) {
        return "risky" + violation + ".length()";
    }

    private static List<String> methodBody(int violation) {
        return List.of(
                "",
                "    void method" + violation + "() {",
                "        int seed" + violation + " = " + (violation * 7) + ";",
                "        String risky" + violation + " = lookup(seed" + violation + ");",
                "        System.out.println(" + marker(violation) + ");",
                "    }");
    }

    private static Version generate(Random random) {
        int methodCount = 4 + random.nextInt(5);
        List<Integer> violations = new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of("package demo;", "", "public class Sample {"));
        for (int i = 1; i <= methodCount; i++) {
            violations.add(i);
            lines.addAll(methodBody(i));
        }
        lines.add("}");
        return new Version("src/main/java/demo/Sample.java", List.copyOf(lines), List.copyOf(violations));
    }

    private static Version apply(Edit edit, Version base, Random random) {
        return switch (edit) {
            case INSERT_LINES_ABOVE -> insertLines(base, random);
            case DELETE_UNRELATED_LINES -> deleteUnrelatedLine(base, random);
            case MOVE_BLOCK -> moveBlock(base, random);
            case RENAME_FILE -> new Version("src/main/java/demo/Renamed.java", base.lines(), base.violations());
            case REINDENT -> reindent(base);
            case FIX_ONE_VIOLATION -> removeMethod(base, random);
            case INTRODUCE_ONE_VIOLATION -> addMethod(base, random);
            case COMBINED -> combined(base, random);
        };
    }

    private static Version combined(Version base, Random random) {
        Version result = insertLines(base, random);
        result = moveBlock(result, random);
        result = removeMethod(result, random);
        result = addMethod(result, random);
        return reindent(result);
    }

    private static Version insertLines(Version base, Random random) {
        List<String> lines = new ArrayList<>(base.lines());
        int count = 1 + random.nextInt(5);
        int at = 1 + random.nextInt(lines.size() - 1);
        for (int i = 0; i < count; i++) {
            lines.add(at + i, "    // note " + random.nextInt(100_000));
        }
        return new Version(base.path(), List.copyOf(lines), base.violations());
    }

    private static Version deleteUnrelatedLine(Version base, Random random) {
        int victim = base.violations().get(random.nextInt(base.violations().size()));
        String seedLine = "int seed" + victim + " =";
        List<String> lines = new ArrayList<>(base.lines());
        lines.removeIf(line -> line.contains(seedLine));
        return new Version(base.path(), List.copyOf(lines), base.violations());
    }

    private static Version moveBlock(Version base, Random random) {
        int moved = base.violations().get(random.nextInt(base.violations().size()));
        List<String> lines = new ArrayList<>(base.lines());
        int[] range = methodRange(lines, moved);
        List<String> block = List.copyOf(lines.subList(range[0], range[1] + 1));
        lines.subList(range[0], range[1] + 1).clear();
        List<Integer> boundaries = methodBoundaries(lines);
        lines.addAll(boundaries.get(random.nextInt(boundaries.size())), block);
        return new Version(base.path(), List.copyOf(lines), base.violations());
    }

    /**
     * The half-open positions a method may be moved to: before another method, or at the end of the
     * class. Methods move between methods in real code, and dropping one into the middle of another
     * would only test the generator's tolerance for nonsense.
     */
    private static List<Integer> methodBoundaries(List<String> lines) {
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("void method")) {
                boundaries.add(i);
            }
        }
        boundaries.add(lines.size() - 1);
        return boundaries;
    }

    /**
     * Inclusive line-index range of a generated method, from its leading blank line to the line
     * that closes it. Found by brace balancing so that it keeps working after other edits have
     * inserted lines into the method.
     */
    private static int[] methodRange(List<String> lines, int violation) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("void method" + violation + "()")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("Method for violation " + violation + " not found");
        }
        int depth = 0;
        int end = start;
        for (int i = start; i < lines.size(); i++) {
            for (char c : lines.get(i).toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (depth == 0) {
                end = i;
                break;
            }
        }
        if (start > 0 && lines.get(start - 1).isBlank()) {
            start--;
        }
        return new int[] {start, end};
    }

    private static Version reindent(Version base) {
        List<String> lines = base.lines().stream()
                .map(line -> line.replace("    ", "\t\t"))
                .toList();
        return new Version(base.path(), lines, base.violations());
    }

    private static Version removeMethod(Version base, Random random) {
        int victim = base.violations().get(random.nextInt(base.violations().size()));
        List<String> lines = new ArrayList<>(base.lines());
        int[] range = methodRange(lines, victim);
        lines.subList(range[0], range[1] + 1).clear();
        List<Integer> violations = new ArrayList<>(base.violations());
        violations.remove(Integer.valueOf(victim));
        return new Version(base.path(), List.copyOf(lines), List.copyOf(violations));
    }

    private static Version addMethod(Version base, Random random) {
        int fresh = 500 + random.nextInt(400);
        while (base.violations().contains(fresh)) {
            fresh++;
        }
        List<String> lines = new ArrayList<>(base.lines());
        int insertAt = Math.max(3, lines.size() - 1);
        lines.addAll(insertAt, indentLike(base, methodBody(fresh)));
        List<Integer> violations = new ArrayList<>(base.violations());
        violations.add(fresh);
        return new Version(base.path(), List.copyOf(lines), List.copyOf(violations));
    }

    /** Keeps a generated method consistent with a version that has already been reindented. */
    private static List<String> indentLike(Version base, List<String> block) {
        boolean tabbed = base.lines().stream().anyMatch(line -> line.startsWith("\t\t"));
        return tabbed ? block.stream().map(line -> line.replace("    ", "\t\t")).toList() : block;
    }

}
