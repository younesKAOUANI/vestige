package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The worked example from {@code README.md} and {@code docs/matching.md}, executed.
 *
 * <p>Two real commits of one file, three findings before and three after, and every interesting
 * outcome at once: an issue that merely moved, an issue whose whole method moved, an issue that was
 * fixed, and a genuinely new one. The file contents here are the same text the documentation shows,
 * so the documentation cannot drift away from the behaviour.
 */
class IssueMatchingWorkedExampleTest {

    private static final String PATH = "src/main/java/com/example/OrderService.java";

    /** Commit a1b2c3d. */
    private static final String BEFORE = """
            package com.example;

            import java.util.List;

            public class OrderService {

                private final OrderRepository repository;

                public OrderService(OrderRepository repository) {
                    this.repository = repository;
                }

                public Order findOrder(String id) {
                    Order order = repository.find(id);
                    return order.normalise();
                }

                public double total(List<Order> orders) {
                    double sum = 0;
                    for (Order order : orders) {
                        sum += order.amount();
                    }
                    return sum;
                }

                public void refresh() {
                    try {
                        repository.reload();
                    } catch (Exception e) {
                        return;
                    }
                }
            }
            """;

    /**
     * Commit e4f5a6b: an audit-log dependency is injected, {@code total} is moved above
     * {@code findOrder}, {@code refresh} is deleted and {@code latest} is added.
     */
    private static final String AFTER = """
            package com.example;

            import java.util.List;
            import java.util.Objects;

            public class OrderService {

                private final OrderRepository repository;
                private final AuditLog auditLog;

                public OrderService(OrderRepository repository, AuditLog auditLog) {
                    this.repository = Objects.requireNonNull(repository);
                    this.auditLog = Objects.requireNonNull(auditLog);
                }

                public double total(List<Order> orders) {
                    double sum = 0;
                    for (Order order : orders) {
                        sum += order.amount();
                    }
                    return sum;
                }

                public Order findOrder(String id) {
                    Order order = repository.find(id);
                    return order.normalise();
                }

                public Order latest() {
                    return repository.latest().normalise();
                }
            }
            """;

    private static final String NULL_DEREFERENCE = "java:S2259";
    private static final String INT_ARITHMETIC = "java:S2184";
    private static final String SWALLOWED_EXCEPTION = "java:S1181";

    @Test
    @DisplayName("tracks three issues across two commits and explains how it tracked each one")
    void tracksIssuesAcrossTwoCommits() {
        List<String> beforeLines = BEFORE.lines().toList();
        List<String> afterLines = AFTER.lines().toList();
        SourceSnapshot before = SourceSnapshot.ofFileContents(Map.of(PATH, BEFORE));
        SourceSnapshot after = SourceSnapshot.ofFileContents(Map.of(PATH, AFTER));

        int nullDerefBefore = lineOf(beforeLines, "return order.normalise();");
        int arithmeticBefore = lineOf(beforeLines, "sum += order.amount();");
        int swallowedBefore = lineOf(beforeLines, "} catch (Exception e) {");

        List<TrackedIssue> tracked = List.of(
                issue("ISSUE-1", NULL_DEREFERENCE, nullDerefBefore, before),
                issue("ISSUE-2", INT_ARITHMETIC, arithmeticBefore, before),
                issue("ISSUE-3", SWALLOWED_EXCEPTION, swallowedBefore, before));

        int nullDerefAfter = lineOf(afterLines, "return order.normalise();");
        int arithmeticAfter = lineOf(afterLines, "sum += order.amount();");
        int newNullDeref = lineOf(afterLines, "return repository.latest().normalise();");

        List<CandidateFinding> incoming = List.of(
                finding("R0", NULL_DEREFERENCE, nullDerefAfter, after),
                finding("R1", INT_ARITHMETIC, arithmeticAfter, after),
                finding("R2", NULL_DEREFERENCE, newNullDeref, after));

        DiffModel diff = UnifiedDiffParser.parse(
                TestUnifiedDiff.between(PATH, beforeLines, PATH, afterLines));

        MatchResult result = new IssueMatcher().match(new MatchRequest(tracked, incoming, diff));

        // ISSUE-1 and ISSUE-2 survive; both keep their identity.
        assertThat(result.matches())
                .extracting(m -> m.issue().id() + " -> " + m.finding().id())
                .containsExactlyInAnyOrder("ISSUE-1 -> R0", "ISSUE-2 -> R1");

        Map<String, IssueMatch> byIssue = result.byIssueId();
        assertThat(byIssue.get("ISSUE-1").finding().location().startLine()).isEqualTo(nullDerefAfter);
        assertThat(byIssue.get("ISSUE-2").finding().location().startLine()).isEqualTo(arithmeticAfter);

        // The deleted try/catch takes its issue with it.
        assertThat(result.disappearedIssues())
                .extracting(TrackedIssue::id)
                .containsExactly("ISSUE-3");

        // The new method's dereference is a new issue, not a resurrection of ISSUE-1.
        assertThat(result.newFindings()).extracting(CandidateFinding::id).containsExactly("R2");

        // Every match records how it was made, and each was made on the strongest evidence there
        // was: the diff kept up with `total`, so ISSUE-2 is a remapped line; it lost `findOrder`,
        // whose lines it reports as deleted and re-added, so ISSUE-1 falls to the structural hash.
        assertThat(byIssue.get("ISSUE-2").strategy()).isEqualTo(MatchStrategy.DIFF_REMAPPED_LINE);
        assertThat(byIssue.get("ISSUE-1").strategy()).isEqualTo(MatchStrategy.STRUCTURAL_HASH);
        assertThat(result.matches())
                .allSatisfy(match -> assertThat(match.confidence()).isGreaterThanOrEqualTo(0.8));
    }

    @Test
    @DisplayName("a method the diff reports as deleted and re-added is tracked structurally")
    void relocatedMethodIsTrackedStructurally() {
        List<String> beforeLines = BEFORE.lines().toList();
        List<String> afterLines = AFTER.lines().toList();
        SourceSnapshot before = SourceSnapshot.ofFileContents(Map.of(PATH, BEFORE));
        SourceSnapshot after = SourceSnapshot.ofFileContents(Map.of(PATH, AFTER));

        int beforeLine = lineOf(beforeLines, "return order.normalise();");
        int afterLine = lineOf(afterLines, "return order.normalise();");

        DiffModel diff = UnifiedDiffParser.parse(
                TestUnifiedDiff.between(PATH, beforeLines, PATH, afterLines));

        // The diff genuinely cannot help here: it represents the relocation of findOrder as a
        // deletion followed by an insertion, so the old line has no head-commit coordinate at all.
        assertThat(diff.translateLine(PATH, beforeLine)).isEmpty();

        TrackedIssue nullDereference = issue("ISSUE-1", NULL_DEREFERENCE, beforeLine, before);
        CandidateFinding sighting = finding("R0", NULL_DEREFERENCE, afterLine, after);

        MatchResult result = new IssueMatcher()
                .match(new MatchRequest(List.of(nullDereference), List.of(sighting), diff));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).strategy()).isEqualTo(MatchStrategy.STRUCTURAL_HASH);
    }

    @Test
    @DisplayName("without the diff the surviving issues are still tracked, on weaker evidence")
    void degradesGracefullyWithoutTheDiff() {
        List<String> beforeLines = BEFORE.lines().toList();
        List<String> afterLines = AFTER.lines().toList();
        SourceSnapshot before = SourceSnapshot.ofFileContents(Map.of(PATH, BEFORE));
        SourceSnapshot after = SourceSnapshot.ofFileContents(Map.of(PATH, AFTER));

        List<TrackedIssue> tracked = List.of(
                issue("ISSUE-1", NULL_DEREFERENCE, lineOf(beforeLines, "return order.normalise();"), before),
                issue("ISSUE-2", INT_ARITHMETIC, lineOf(beforeLines, "sum += order.amount();"), before));
        List<CandidateFinding> incoming = List.of(
                finding("R0", NULL_DEREFERENCE, lineOf(afterLines, "return order.normalise();"), after),
                finding("R1", INT_ARITHMETIC, lineOf(afterLines, "sum += order.amount();"), after));

        MatchResult result =
                new IssueMatcher().match(new MatchRequest(tracked, incoming, DiffModel.empty()));

        assertThat(result.matches())
                .extracting(m -> m.issue().id() + " -> " + m.finding().id())
                .containsExactlyInAnyOrder("ISSUE-1 -> R0", "ISSUE-2 -> R1");
        assertThat(result.matches())
                .allSatisfy(match ->
                        assertThat(match.strategy()).isEqualTo(MatchStrategy.STRUCTURAL_HASH));
    }

    private static TrackedIssue issue(String id, String ruleId, int line, SourceSnapshot snapshot) {
        SourceLocation location = SourceLocation.ofLine(PATH, line);
        return new TrackedIssue(
                id,
                ruleId,
                Severity.MAJOR,
                IssueStatus.OPEN,
                location,
                new FingerprintCalculator(snapshot).compute(ruleId, location, null));
    }

    private static CandidateFinding finding(
            String id, String ruleId, int line, SourceSnapshot snapshot) {
        SourceLocation location = SourceLocation.ofLine(PATH, line);
        return new CandidateFinding(
                id,
                ruleId,
                Severity.MAJOR,
                ruleId + " reported here",
                location,
                new FingerprintCalculator(snapshot).compute(ruleId, location, null));
    }

    /** One-based line number of the only line containing {@code needle}. */
    private static int lineOf(List<String> lines, String needle) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                hits.add(i + 1);
            }
        }
        assertThat(hits).as("occurrences of '%s'", needle).hasSize(1);
        return hits.get(0);
    }
}
