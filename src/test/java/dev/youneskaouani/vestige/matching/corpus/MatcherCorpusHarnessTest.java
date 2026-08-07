package dev.youneskaouani.vestige.matching.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The most important test in the repository.
 *
 * <p>Everything else about §3.3 can be correct in isolation - rung order, tie-breaking, proximity
 * windows - and the matcher can still be wrong about what a real refactor looks like. This test is
 * the thing that actually checks that: it loads every hand-authored before/after pair in {@code
 * matcher-corpus/cases/} (at least 25, per the architecture doc's own bar), runs each one through
 * the production {@link dev.youneskaouani.vestige.matching.IssueMatcher}, and enforces the
 * project's stated acceptance thresholds - <b>zero</b> tolerance for false merges (silently
 * conflating two different issues is worse than any inconvenience a split causes), and at most 5%
 * false splits (losing an issue's history occasionally is a real cost, not a free one, which is why
 * the bar is 5% and not 100%).
 *
 * <p>A failure here means the algorithm regressed against a concrete, named refactor shape - the
 * failure list in the assertion message says exactly which fixture and which pairing.
 */
class MatcherCorpusHarnessTest {

    /** Matches {@code vestige.matching.weak-fingerprint-line-proximity} in application.yml. */
    private static final int WEAK_LINE_PROXIMITY = 25;

    private static final int MINIMUM_CASE_COUNT = 25;
    private static final double MAX_FALSE_SPLIT_RATE = 0.05;

    @Test
    void matcherCorpusMeetsTheAccuracyBar() {
        MatcherCorpusHarness harness = new MatcherCorpusHarness(WEAK_LINE_PROXIMITY);

        Path directory = CorpusLocator.locateCasesDirectory();
        List<CorpusCase> cases = harness.loadCases(directory);

        assertThat(cases.size())
                .as(
                        "matcher-corpus/cases must contain at least %d hand-authored fixtures",
                        MINIMUM_CASE_COUNT)
                .isGreaterThanOrEqualTo(MINIMUM_CASE_COUNT);

        MatcherCorpusHarness.Report report = harness.evaluate(cases);
        System.out.println(report.summary());
        report.failures().forEach(System.out::println);

        assertThat(report.falseMergeRate())
                .as(
                        "false merges silently conflate two different issues - zero tolerance.\n%s\n%s",
                        report.summary(), String.join("\n", report.failures()))
                .isZero();
        assertThat(report.falseSplitRate())
                .as(
                        "false splits lose an issue's tracked history across the refactor.\n%s\n%s",
                        report.summary(), String.join("\n", report.failures()))
                .isLessThanOrEqualTo(MAX_FALSE_SPLIT_RATE);
    }
}
