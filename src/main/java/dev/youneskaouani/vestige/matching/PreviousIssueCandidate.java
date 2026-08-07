package dev.youneskaouani.vestige.matching;

import java.util.UUID;

/**
 * One previously-tracked issue's most recent sighting, as the matcher sees it - the "P" side of
 * §3.3's {@code match(P, C)}.
 *
 * @param issueId the issue this candidate represents; matched at most once per call to {@link
 *     IssueMatcher#match}
 * @param tieBreakSeq the {@code finding.seq} of the sighting this candidate is built from - "lowest
 *     finding id" in §3.3's tie-break, using the bigint identity column rather than the finding's
 *     UUID because a UUID has no meaningful order (see the {@code seq} column comment in
 *     V1__core_schema.sql)
 * @param line the sighting's line number, for line-proximity tie-breaking and the rung-3 window
 * @param fingerprints computed by {@link FingerprintFactory} <em>after</em> the commit's rename map
 *     has been applied to this candidate's file path
 */
public record PreviousIssueCandidate(
        UUID issueId, long tieBreakSeq, int line, Fingerprints fingerprints) {}
