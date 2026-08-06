package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared implementation for the passes that reduce an issue and a finding to a string key and pair
 * the ones whose keys are equal.
 *
 * <p>Four of the five passes have that shape; only the positional fallback, which matches on a
 * range rather than a value, does not. Having them share this class means they also share their
 * determinism guarantees, which are the subtle part:
 *
 * <ul>
 *   <li>keys are iterated through a {@link TreeMap}, so the order buckets are processed in does not
 *       depend on hash codes;
 *   <li>the members of a bucket keep the canonical order the workspace imposed;
 *   <li>when a key is ambiguous — <em>n</em> issues and <em>m</em> findings sharing it — the first
 *       {@code min(n, m)} of each are paired positionally and the surplus is left for later passes.
 *       Pairing some of an ambiguous bucket rather than none keeps issue history alive through
 *       copy-paste duplication, and doing it in canonical order keeps it reproducible.
 * </ul>
 *
 * <p>A {@code null} key means "this pass cannot say anything about this item" and is skipped; it
 * never matches another null.
 */
abstract class KeyedMatchPass implements MatchPass {

    /** The key of a previously tracked issue, or null when this pass cannot compute one. */
    protected abstract String issueKey(TrackedIssue issue, DiffModel diff);

    /** The key of an incoming finding, or null when this pass cannot compute one. */
    protected abstract String findingKey(CandidateFinding finding, DiffModel diff);

    /**
     * Last-chance veto on an otherwise key-equal pair. Passes that match on content have nothing to
     * add here; the pass that matches on a predicted position uses it to refuse pairs that the
     * content contradicts.
     */
    protected boolean admissible(TrackedIssue issue, CandidateFinding finding) {
        return true;
    }

    @Override
    public final void run(MatchingWorkspace workspace) {
        DiffModel diff = workspace.diff();

        Map<String, List<TrackedIssue>> issuesByKey = new TreeMap<>();
        for (TrackedIssue issue : workspace.unclaimedIssues()) {
            String key = issueKey(issue, diff);
            if (key != null) {
                issuesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(issue);
            }
        }
        if (issuesByKey.isEmpty()) {
            return;
        }

        Map<String, List<CandidateFinding>> findingsByKey = new LinkedHashMap<>();
        for (CandidateFinding finding : workspace.unclaimedFindings()) {
            String key = findingKey(finding, diff);
            if (key != null) {
                findingsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(finding);
            }
        }

        for (Map.Entry<String, List<TrackedIssue>> entry : issuesByKey.entrySet()) {
            List<CandidateFinding> candidates = findingsByKey.get(entry.getKey());
            if (candidates == null) {
                continue;
            }
            boolean[] taken = new boolean[candidates.size()];
            for (TrackedIssue issue : entry.getValue()) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (taken[i] || !admissible(issue, candidates.get(i))) {
                        continue;
                    }
                    taken[i] = true;
                    workspace.claim(issue, candidates.get(i), strategy());
                    break;
                }
            }
        }
    }

    /** ASCII unit separator: cannot occur in a rule id, a path or a hex hash. */
    private static final String KEY_SEPARATOR = "\u001F";

    /** Joins key components unambiguously. */
    protected static String key(String... parts) {
        return String.join(KEY_SEPARATOR, parts);
    }
}
