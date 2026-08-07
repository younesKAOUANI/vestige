package dev.youneskaouani.vestige.matching;

/**
 * One finding from the run being processed - the "C" side of §3.3's {@code match(P, C)}.
 *
 * @param ordinal the finding's position in this run's parse order; current findings have no
 *     database id yet at match time (§4.2: matching happens before the batch insert that assigns
 *     one), and parse order is itself deterministic for a fixed report, which is what {@link
 *     IssueMatcher} relies on to visit findings in a reproducible sequence
 * @param line the finding's line number
 * @param fingerprints computed once, at parse time, by {@link FingerprintFactory}
 */
public record IncomingFinding(int ordinal, int line, Fingerprints fingerprints) {}
