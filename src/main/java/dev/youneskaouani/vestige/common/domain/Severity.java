package dev.youneskaouani.vestige.common.domain;

import java.util.Locale;

/**
 * Issue severity, ordered from least to most serious.
 *
 * <p>The declaration order <em>is</em> the ranking: quality-gate conditions such as "no new issues
 * of severity &ge; BLOCKER" compare with {@link #isAtLeast(Severity)} rather than string equality.
 */
public enum Severity {
    INFO,
    MINOR,
    MAJOR,
    CRITICAL,
    BLOCKER;

    /** True when this severity is at least as serious as {@code threshold}. */
    public boolean isAtLeast(Severity threshold) {
        return this.ordinal() >= threshold.ordinal();
    }

    /**
     * Maps a SARIF {@code level} to a Vestige severity.
     *
     * <p>SARIF only defines {@code none|note|warning|error}, which is coarser than what analysers
     * actually report. Where the tool also supplies a {@code security-severity} property (the
     * convention GitHub code scanning established) we refine {@code error} upwards.
     */
    public static Severity fromSarif(String level, Double securitySeverity) {
        String normalised = level == null ? "warning" : level.toLowerCase(Locale.ROOT);
        Severity base =
                switch (normalised) {
                    case "error" -> Severity.CRITICAL;
                    case "warning" -> Severity.MAJOR;
                    case "note" -> Severity.MINOR;
                    case "none" -> Severity.INFO;
                    default -> Severity.MAJOR;
                };
        if (securitySeverity != null && securitySeverity >= 9.0) {
            return Severity.BLOCKER;
        }
        return base;
    }
}
