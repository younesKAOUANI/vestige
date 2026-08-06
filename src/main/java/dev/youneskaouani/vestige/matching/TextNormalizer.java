package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * The normalisation rules the fingerprints are built on.
 *
 * <p>Everything the matcher hashes passes through here first. The rules are deliberately small and
 * boring: the more clever the normalisation, the more likely two genuinely different findings
 * collapse onto the same hash and get merged into one issue.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Canonical path form: forward slashes, no leading {@code ./} or {@code /}, no repeated
     * separators.
     *
     * <p>Case is preserved. Lower-casing would make {@code Foo.java} and {@code foo.java} the same
     * file, which is wrong on every platform Vestige actually runs analyses for.
     */
    public static String normalisePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /**
     * Collapses every run of whitespace to a single space and trims the ends.
     *
     * <p>This is what makes the structural and line-content hashes survive a reformat: changing
     * indentation, or wrapping an argument list differently within a line, does not change the
     * normalised text.
     */
    public static String collapseWhitespace(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = !out.isEmpty();
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Normalises a block of source into a single string: each line collapsed, blank lines dropped,
     * joined with {@code \n}.
     *
     * <p>Dropping blank lines matters — inserting a blank line inside a method is one of the most
     * common no-op edits, and it must not change the block's identity.
     */
    public static String normaliseBlock(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            String collapsed = collapseWhitespace(line);
            if (!collapsed.isEmpty()) {
                kept.add(collapsed);
            }
        }
        return String.join("\n", kept);
    }
}
