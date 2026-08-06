package dev.youneskaouani.vestige.matching;

/**
 * Where a finding sits in a file, in one-based SARIF coordinates.
 *
 * <p>Columns and end lines are optional in SARIF; zero means "not reported". Only {@link #path()}
 * and {@link #startLine()} participate in matching, but the full region is stored on the occurrence
 * so that the UI can highlight what the analyser actually pointed at.
 */
public record SourceLocation(String path, int startLine, int endLine, int startColumn, int endColumn) {

    public SourceLocation {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("SourceLocation requires a path");
        }
        if (startLine < 1) {
            throw new IllegalArgumentException("SourceLocation requires a one-based startLine, got " + startLine);
        }
        path = TextNormalizer.normalisePath(path);
        if (endLine < startLine) {
            endLine = startLine;
        }
        if (startColumn < 0) {
            startColumn = 0;
        }
        if (endColumn < 0) {
            endColumn = 0;
        }
    }

    /** Convenience factory for the common case where the analyser only reported a line. */
    public static SourceLocation ofLine(String path, int startLine) {
        return new SourceLocation(path, startLine, startLine, 0, 0);
    }

    /** The same region relocated to a new path and start line, keeping the region's height. */
    public SourceLocation relocatedTo(String newPath, int newStartLine) {
        int height = endLine - startLine;
        return new SourceLocation(newPath, newStartLine, newStartLine + height, startColumn, endColumn);
    }
}
