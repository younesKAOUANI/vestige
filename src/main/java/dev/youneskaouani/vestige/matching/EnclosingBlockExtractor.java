package dev.youneskaouani.vestige.matching;

import java.util.List;

/**
 * Finds the smallest brace-delimited block containing a given line.
 *
 * <p>The structural fingerprint hashes a <em>region</em> of code rather than a single line, so that
 * an issue keeps its identity when the lines around it move. The region has to be picked without a
 * parser: Vestige ingests reports from analysers for languages it does not itself understand, so it
 * cannot assume an AST is available. Brace balancing is the cheapest approximation that is right
 * for the C-family languages most analysers target, and the symmetric-window fallback keeps the
 * fingerprint useful (if weaker) for everything else.
 *
 * <p>The limitations are real and documented in {@code docs/matching.md}: braces inside string
 * literals or comments are counted, and a block larger than {@link #MAX_BLOCK_LINES} is clamped
 * around the finding so that one enormous method does not make every fingerprint in it fragile.
 */
public final class EnclosingBlockExtractor {

    /** Lines above and below the finding used when no brace-delimited block can be found. */
    public static final int FALLBACK_RADIUS = 3;

    /** Upper bound on the size of a structural block. */
    public static final int MAX_BLOCK_LINES = 400;

    private EnclosingBlockExtractor() {
    }

    /**
     * @param lines the whole file, zero-indexed
     * @param oneBasedLine the line the finding points at
     * @return the lines of the enclosing block, never empty as long as the file is not empty
     */
    public static List<String> extract(List<String> lines, int oneBasedLine) {
        if (lines.isEmpty()) {
            return List.of();
        }
        int index = Math.clamp(oneBasedLine - 1L, 0, lines.size() - 1);

        int openLine = findOpeningLine(lines, index);
        if (openLine < 0) {
            return window(lines, index, FALLBACK_RADIUS);
        }
        int closeLine = findClosingLine(lines, openLine);
        if (closeLine < index) {
            return window(lines, index, FALLBACK_RADIUS);
        }
        if (closeLine - openLine + 1 > MAX_BLOCK_LINES) {
            return window(lines, index, MAX_BLOCK_LINES / 2);
        }
        return List.copyOf(lines.subList(openLine, closeLine + 1));
    }

    /**
     * Scans upwards from the finding for the {@code &#123;} that opens the innermost enclosing block.
     *
     * <p>Reading each line right-to-left, a {@code &#125;} means we entered a nested block that
     * closes before us, so it must be skipped; a {@code &#123;} at nesting depth zero is the one we
     * are looking for.
     */
    private static int findOpeningLine(List<String> lines, int fromIndex) {
        int depth = 0;
        for (int i = fromIndex; i >= 0; i--) {
            String line = lines.get(i);
            for (int c = line.length() - 1; c >= 0; c--) {
                char ch = line.charAt(c);
                if (ch == '}') {
                    depth++;
                } else if (ch == '{') {
                    if (depth == 0) {
                        return i;
                    }
                    depth--;
                }
            }
        }
        return -1;
    }

    /** Scans downwards from the opening line for the brace that balances it. */
    private static int findClosingLine(List<String> lines, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return lines.size() - 1;
    }

    private static List<String> window(List<String> lines, int index, int radius) {
        int from = Math.max(0, index - radius);
        int to = Math.min(lines.size(), index + radius + 1);
        return List.copyOf(lines.subList(from, to));
    }
}
