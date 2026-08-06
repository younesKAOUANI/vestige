package dev.youneskaouani.vestige.matching;

import dev.youneskaouani.vestige.common.hash.Sha256;
import java.util.List;
import java.util.Optional;

/**
 * Computes the structural and line-content fingerprints of a finding from a source snapshot.
 *
 * <p>Both hashes are length-prefixed field hashes (see {@link Sha256#hexOfFields(String...)}) so
 * that {@code ruleId="ab", text="c"} and {@code ruleId="a", text="bc"} cannot collide.
 */
public final class FingerprintCalculator {

    private final SourceSnapshot snapshot;

    public FingerprintCalculator(SourceSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Builds the fingerprint triple for one finding.
     *
     * @param exactFromReport the analyser-supplied {@code partialFingerprints} value, may be null
     */
    public Fingerprints compute(String ruleId, SourceLocation location, String exactFromReport) {
        Optional<List<String>> file = snapshot.lines(location.path());
        if (file.isEmpty()) {
            return new Fingerprints(exactFromReport, null, null);
        }
        List<String> lines = file.get();
        return new Fingerprints(
                exactFromReport,
                structuralHash(ruleId, location, lines),
                lineContentHash(ruleId, location, lines));
    }

    /**
     * SHA-256 of the rule, the normalised path and the whitespace-collapsed enclosing block.
     *
     * <p>Because the pre-image contains no line numbers, inserting or deleting lines anywhere
     * outside the block leaves the hash untouched — that is exactly the shift-invariance pass 3
     * exists to provide.
     */
    private String structuralHash(String ruleId, SourceLocation location, List<String> lines) {
        List<String> block = EnclosingBlockExtractor.extract(lines, location.startLine());
        if (block.isEmpty()) {
            return null;
        }
        String normalised = TextNormalizer.normaliseBlock(block);
        if (normalised.isEmpty()) {
            return null;
        }
        return Sha256.hexOfFields("structural", ruleId, location.path(), normalised);
    }

    /**
     * SHA-256 of the rule and the whitespace-collapsed offending line.
     *
     * <p>The path is deliberately absent: this is the pass that has to survive a file being renamed
     * or a block being cut and pasted into a different file.
     */
    private String lineContentHash(String ruleId, SourceLocation location, List<String> lines) {
        int index = location.startLine() - 1;
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        String normalised = TextNormalizer.collapseWhitespace(lines.get(index));
        if (normalised.isEmpty()) {
            return null;
        }
        return Sha256.hexOfFields("line", ruleId, normalised);
    }
}
