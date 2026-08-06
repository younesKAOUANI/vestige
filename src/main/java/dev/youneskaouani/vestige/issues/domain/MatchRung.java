package dev.youneskaouani.vestige.issues.domain;

import dev.youneskaouani.vestige.matching.Rung;

/**
 * {@code finding.match_rung} (V1__core_schema.sql): which rung of §3.3's ladder produced this
 * finding's issue link, surfaced in the UI so a reviewer can see why two runs were considered the
 * same issue - or {@link #NEW} when no rung matched and the finding opened a fresh issue instead.
 *
 * <p>Deliberately not the same type as {@link Rung}: {@code Rung.values()} is what {@code
 * IssueMatcher} iterates to try the three fingerprint rungs in order, and a finding that matched
 * nothing is not a fourth rung the matcher tried - it is the absence of a match. Giving persistence
 * its own enum keeps that iteration from ever seeing a case it would have to skip.
 */
public enum MatchRung {
    IDENTITY,
    CONTEXT,
    WEAK,
    NEW;

    public static MatchRung from(Rung rung) {
        return switch (rung) {
            case IDENTITY -> IDENTITY;
            case CONTEXT -> CONTEXT;
            case WEAK -> WEAK;
        };
    }
}
