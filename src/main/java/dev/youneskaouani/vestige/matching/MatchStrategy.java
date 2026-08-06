package dev.youneskaouani.vestige.matching;

/**
 * The passes of the matcher, in decreasing order of confidence.
 *
 * <p>Declaration order is execution order. Each pass only sees the issues and findings that no
 * earlier pass claimed, so a single enum both names the strategy and fixes the pipeline.
 */
public enum MatchStrategy {

    /** The analyser supplied a stable {@code partialFingerprints} entry and it matched exactly. */
    EXACT_FINGERPRINT(1.0),

    /** The previous line number, translated into head-commit coordinates through the diff, matched. */
    DIFF_REMAPPED_LINE(0.9),

    /** The hash of the normalised enclosing code block matched. Survives line shifts. */
    STRUCTURAL_HASH(0.8),

    /** The hash of the normalised offending line matched. Survives file moves and renames. */
    LINE_CONTENT_HASH(0.6),

    /** Same rule and file, and the line drifted by no more than the configured window. */
    POSITIONAL_FALLBACK(0.3);

    private final double confidence;

    MatchStrategy(double confidence) {
        this.confidence = confidence;
    }

    /** The confidence recorded on every occurrence this strategy produces. */
    public double confidence() {
        return confidence;
    }
}
