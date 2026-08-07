package dev.youneskaouani.vestige.matching;

import java.util.regex.Pattern;

/**
 * Computes §3.2's {@code normalised_line_hash} input: "the flagged line, stripped of
 * leading/trailing whitespace, with all string and numeric literals replaced by {@code §}".
 *
 * <p>This is a heuristic, character-level scanner, not a lexer for any one language - SARIF is
 * language-agnostic and Vestige has no compiler front end to lean on (§11 rules that out
 * explicitly: ADR-010 rejects "language-specific AST parsing in Vestige itself"). Two consequences
 * follow, and both are exercised in {@code matcher-corpus/}:
 *
 * <ul>
 *   <li>it recognises single- and double-quoted literals with backslash escaping, which covers
 *       every C-family, Java, Python, JavaScript and Go source line Vestige is likely to see, but
 *       not a triple-quoted or other multi-line string opened on this line - those are left as
 *       literal text, which only matters if the literal itself changes between runs;
 *   <li>a numeric literal is recognised by a digit boundary, so a leading sign is deliberately left
 *       out of the match ({@code x - 1} and {@code x = -1} both keep their {@code -}) rather than
 *       guessing whether it is a sign or a subtraction operator.
 * </ul>
 */
public final class LineNormalizer {

    private static final String PLACEHOLDER = "§";

    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile(
                    "\\b0[xX][0-9a-fA-F]+[lL]?\\b" // hexadecimal, tried first so it is not read as
                            // "0"
                            + "|\\b\\d[\\d_]*(\\.[\\d_]+)?([eE][+-]?\\d+)?[fFdDlL]?\\b");

    private LineNormalizer() {}

    /**
     * @return the normalised line, or {@code null}/blank if {@code rawLine} was, since an empty
     *     snippet carries no information a hash should pretend to summarise
     */
    public static String normalize(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String trimmed = rawLine.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        String literalsMasked = maskQuotedLiterals(trimmed);
        return NUMERIC_LITERAL.matcher(literalsMasked).replaceAll(PLACEHOLDER);
    }

    private static String maskQuotedLiterals(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int closingIndex = indexOfClosingQuote(text, i, c);
                out.append(PLACEHOLDER);
                i = closingIndex + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Scans past backslash-escaped characters; an unterminated quote is masked to end of line. */
    private static int indexOfClosingQuote(String text, int openingIndex, char quote) {
        int i = openingIndex + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i;
            }
            i++;
        }
        return text.length() - 1;
    }
}
