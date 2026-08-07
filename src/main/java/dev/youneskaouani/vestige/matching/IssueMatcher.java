package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements §3.3's matching algorithm exactly:
 *
 * <pre>{@code
 * match(previousOpenIssues P, currentFindings C):
 *     apply rename map to P
 *     unmatched_P <- P ; unmatched_C <- C ; matches <- {}
 *
 *     for rung in [identity_fp, context_fp, weak_fp]:
 *         buckets <- group unmatched_P by rung fingerprint
 *         for c in unmatched_C:
 *             candidates <- buckets[fingerprint(c, rung)]
 *             if candidates is empty: continue
 *             if rung is weak_fp:
 *                 candidates <- filter |line(candidate) - line(c)| <= 25
 *             best <- argmin over candidates of |line(candidate) - line(c)|   <- stable tie-break
 *             matches <- matches u {(best, c)} ; remove both from unmatched
 *         # a rung completes fully before the next begins: strong evidence always wins
 *
 *     for (p, c) in matches:       p.lastSeen <- run ; attach c to p
 *     for c in unmatched_C:        open new Issue (introduced at this commit)
 *     for p in unmatched_P:        p.status <- RESOLVED_FIXED (auto)
 * }</pre>
 *
 * <p>The rename map is applied by the caller before {@link #match}: {@code
 * PreviousIssueCandidate.fingerprints()} is expected to already be computed against the post-rename
 * path (see {@link FingerprintFactory}'s javadoc and {@code IssueMatchingService}). This class does
 * not know about commits, SCM providers, or persistence - it is a pure function of two lists, which
 * is what lets it be replayed (§4.2) and unit-tested without a database.
 *
 * <p><b>Complexity.</b> Each rung builds one bucket map from the still-unmatched previous
 * candidates (O(|P|)) and does one pass over the still-unmatched current findings, each a O(1) hash
 * lookup plus a scan confined to that finding's own bucket. For the two content fingerprints
 * (identity, context) those buckets are, in practice, singletons or near enough - collisions mean
 * "these are genuinely the same claim" or a genuine hash collision, both rare - so the algorithm is
 * effectively O(|P| + |C|), which is the whole point: the naive alternative is comparing every
 * current finding against every previous issue, O(|P| x |C|), and on a 50k-finding repository that
 * is milliseconds against minutes (§3.3). The one rung where a bucket can legitimately be large is
 * {@code weak_fp}, which groups only by rule and file: a file with many issues of the same rule
 * scans a bucket proportional to that file's issue count for that rule, not to the whole project.
 * That is a real, bounded cost of using the least specific rung, not an oversight, and it is why
 * the ladder tries the other two first.
 */
public final class IssueMatcher {

    private final int weakLineProximity;

    public IssueMatcher(int weakLineProximity) {
        if (weakLineProximity < 0) {
            throw new IllegalArgumentException("weakLineProximity must not be negative");
        }
        this.weakLineProximity = weakLineProximity;
    }

    public MatchResult match(List<PreviousIssueCandidate> previous, List<IncomingFinding> current) {
        Map<UUID, PreviousIssueCandidate> unmatchedPrevious = new LinkedHashMap<>();
        for (PreviousIssueCandidate candidate : previous) {
            if (unmatchedPrevious.put(candidate.issueId(), candidate) != null) {
                throw new IllegalArgumentException(
                        "Duplicate candidate for issue " + candidate.issueId());
            }
        }

        boolean[] consumed = new boolean[current.size()];
        List<Match> matches = new ArrayList<>();

        for (Rung rung : Rung.values()) {
            Map<String, List<PreviousIssueCandidate>> buckets =
                    bucketByRung(unmatchedPrevious.values(), rung);

            // Current findings are visited in their fixed, deterministic parse order (§3.3's
            // determinism requirement): the same report bytes always produce the same finding
            // order, so ties between two current findings claiming the same bucket resolve the
            // same way on every replay.
            for (int i = 0; i < current.size(); i++) {
                if (consumed[i]) {
                    continue;
                }
                IncomingFinding incoming = current.get(i);
                String key = rung.fingerprintOf(incoming.fingerprints());
                if (key == null) {
                    continue;
                }
                List<PreviousIssueCandidate> bucketed = buckets.get(key);
                if (bucketed == null || bucketed.isEmpty()) {
                    continue;
                }
                PreviousIssueCandidate best =
                        selectBest(bucketed, incoming, rung, unmatchedPrevious);
                if (best == null) {
                    continue;
                }
                matches.add(new Match(best, incoming, rung));
                unmatchedPrevious.remove(best.issueId());
                consumed[i] = true;
            }
            // Rung complete: everything left in unmatchedPrevious and every un-consumed current
            // finding falls through to the next, weaker rung.
        }

        List<IncomingFinding> newIssues = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            if (!consumed[i]) {
                newIssues.add(current.get(i));
            }
        }
        return new MatchResult(matches, newIssues, List.copyOf(unmatchedPrevious.values()));
    }

    /**
     * §3.3's {@code best <- argmin over candidates of |line(candidate) - line(c)|}, with the
     * documented tie-break ("stable tie-break" in the pseudocode, spelled out in prose as "line
     * proximity, then lowest finding id") applied when two candidates are equally close.
     *
     * <p>{@code bucketed} is a snapshot taken at the top of this rung; a candidate claimed by an
     * earlier {@code incoming} finding within this same rung is skipped via the live {@code
     * unmatchedPrevious} check rather than by rebuilding every bucket after each match, which is
     * what keeps a rung's cost linear in its own bucket sizes.
     */
    private PreviousIssueCandidate selectBest(
            List<PreviousIssueCandidate> bucketed,
            IncomingFinding incoming,
            Rung rung,
            Map<UUID, PreviousIssueCandidate> unmatchedPrevious) {

        Comparator<PreviousIssueCandidate> byProximityThenLowestId =
                Comparator.<PreviousIssueCandidate>comparingInt(
                                candidate -> Math.abs(candidate.line() - incoming.line()))
                        .thenComparingLong(PreviousIssueCandidate::tieBreakSeq);

        PreviousIssueCandidate best = null;
        for (PreviousIssueCandidate candidate : bucketed) {
            if (!unmatchedPrevious.containsKey(candidate.issueId())) {
                continue;
            }
            if (rung == Rung.WEAK
                    && Math.abs(candidate.line() - incoming.line()) > weakLineProximity) {
                continue;
            }
            if (best == null || byProximityThenLowestId.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, List<PreviousIssueCandidate>> bucketByRung(
            Collection<PreviousIssueCandidate> candidates, Rung rung) {
        Map<String, List<PreviousIssueCandidate>> buckets = new HashMap<>();
        for (PreviousIssueCandidate candidate : candidates) {
            String key = rung.fingerprintOf(candidate.fingerprints());
            if (key == null) {
                continue;
            }
            buckets.computeIfAbsent(key, unused -> new ArrayList<>()).add(candidate);
        }
        return buckets;
    }
}
