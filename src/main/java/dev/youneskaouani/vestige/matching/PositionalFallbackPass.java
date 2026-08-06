package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Pass 5 — same rule, same file, and the line only drifted a little.
 *
 * <p>This is the pass that exists because the four above it can all be defeated at once: an
 * analyser that emits no fingerprints, a CI job that forgot to attach the diff, a report without
 * embedded sources, and an edit that rewrote the offending line. Something still has to keep the
 * issue's history rather than resolving it and immediately opening a clone, and "the same rule
 * fires within ten lines of where it fired before" is weak but usually right.
 *
 * <p>It is weak enough that it is worth being explicit about the failure mode: two genuinely
 * different violations of the same rule, close together, where one is fixed in the same commit that
 * introduces the other, will be reported as one issue that moved. The confidence of 0.3 is how that
 * uncertainty reaches the API, and it is why the strategy is stored on every occurrence.
 *
 * <p>Unlike the keyed passes this one matches on a range, so it resolves competition explicitly:
 * every admissible pair is enumerated, ordered by distance first and by canonical identity second,
 * and claimed greedily. Nearest-first greedy over a totally ordered candidate list is deterministic
 * and independent of the order the caller supplied.
 */
final class PositionalFallbackPass implements MatchPass {

    /** ASCII unit separator: cannot occur in a rule id or a path. */
    private static final char KEY_SEPARATOR = '\u001F';

    private final int driftWindow;

    PositionalFallbackPass(int driftWindow) {
        this.driftWindow = driftWindow;
    }

    @Override
    public MatchStrategy strategy() {
        return MatchStrategy.POSITIONAL_FALLBACK;
    }

    @Override
    public void run(MatchingWorkspace workspace) {
        if (driftWindow < 0) {
            return;
        }
        DiffModel diff = workspace.diff();

        Map<String, List<CandidateFinding>> findingsByGroup = new LinkedHashMap<>();
        for (CandidateFinding finding : workspace.unclaimedFindings()) {
            findingsByGroup
                    .computeIfAbsent(group(finding.ruleId(), finding.location().path()), k -> new ArrayList<>())
                    .add(finding);
        }
        if (findingsByGroup.isEmpty()) {
            return;
        }

        List<Pair> pairs = new ArrayList<>();
        for (TrackedIssue issue : workspace.unclaimedIssues()) {
            String headPath = diff.mapPath(issue.location().path());
            List<CandidateFinding> candidates = findingsByGroup.get(group(issue.ruleId(), headPath));
            if (candidates == null) {
                continue;
            }
            int reference = referenceLine(issue, diff);
            for (CandidateFinding candidate : candidates) {
                int distance = Math.abs(candidate.location().startLine() - reference);
                if (distance <= driftWindow && !ContentEvidence.contradicts(issue, candidate)) {
                    pairs.add(new Pair(distance, issue, candidate));
                }
            }
        }

        pairs.sort(PAIR_ORDER);
        Set<String> claimedIssues = new HashSet<>();
        Set<String> claimedFindings = new HashSet<>();
        for (Pair pair : pairs) {
            if (claimedIssues.contains(pair.issue.id()) || claimedFindings.contains(pair.finding.id())) {
                continue;
            }
            claimedIssues.add(pair.issue.id());
            claimedFindings.add(pair.finding.id());
            workspace.claim(pair.issue, pair.finding, strategy());
        }
    }

    /**
     * Where we expect the issue to be in the head commit: the diff-translated line when the diff
     * knows, otherwise the line it was last seen on.
     */
    private static int referenceLine(TrackedIssue issue, DiffModel diff) {
        OptionalInt translated = diff.translateLine(issue.location().path(), issue.location().startLine());
        return translated.orElseGet(() -> issue.location().startLine());
    }

    private static String group(String ruleId, String path) {
        return ruleId + KEY_SEPARATOR + path;
    }

    private record Pair(int distance, TrackedIssue issue, CandidateFinding finding) {
    }

    private static final Comparator<Pair> PAIR_ORDER =
            Comparator.comparingInt(Pair::distance)
                    .thenComparingInt(p -> p.finding().location().startLine())
                    .thenComparing(p -> p.finding().id())
                    .thenComparing(p -> p.issue().id());
}
