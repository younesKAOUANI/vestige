package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the unified diff format that {@code git diff} produces.
 *
 * <p>Vestige parses the diff itself rather than shelling out to git or pulling in a diff library:
 * the service never has a working copy, CI hands it a patch as text, and the only thing we need out
 * of that text is a line-number mapping and the set of changed lines. The format we accept is the
 * one {@code git diff} and {@code git format-patch} emit, including rename headers and hunks with
 * an omitted count.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static final Pattern GIT_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");

    private UnifiedDiffParser() {
    }

    /** Parses a unified diff. A null or blank input yields an empty model rather than an error. */
    public static DiffModel parse(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return DiffModel.empty();
        }

        List<FileDiff> files = new ArrayList<>();
        State state = new State();

        String[] rawLines = diffText.split("\n", -1);
        // A diff ends with a newline, so splitting leaves a trailing empty element that is an
        // artefact of the terminator rather than a line of the patch. A genuinely empty line in a
        // hunk body does exist though - some tools strip the trailing space from an empty context
        // line - so only the very last one is dropped.
        int lineCount = rawLines.length;
        if (lineCount > 0 && rawLines[lineCount - 1].isEmpty()) {
            lineCount--;
        }

        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            String line = stripCarriageReturn(rawLines[lineIndex]);

            Matcher gitHeader = GIT_HEADER.matcher(line);
            if (gitHeader.matches()) {
                state.flushInto(files);
                state.startFile(gitHeader.group(1), gitHeader.group(2));
                continue;
            }
            if (line.startsWith("rename from ")) {
                state.oldPath = line.substring("rename from ".length()).trim();
                continue;
            }
            if (line.startsWith("rename to ")) {
                state.newPath = line.substring("rename to ".length()).trim();
                continue;
            }
            if (line.startsWith("--- ")) {
                // Plain "diff -u" output has no "diff --git" header, so a "---" line is the only
                // signal that the previous file is finished.
                if (state.hasCompleteFile()) {
                    state.flushInto(files);
                }
                state.flushHunk();
                state.oldPath = stripPathPrefix(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("+++ ")) {
                state.newPath = stripPathPrefix(line.substring(4).trim());
                continue;
            }

            Matcher hunkHeader = HUNK_HEADER.matcher(line);
            if (hunkHeader.matches()) {
                state.flushHunk();
                state.beginHunk(
                        Integer.parseInt(hunkHeader.group(1)),
                        parseCount(hunkHeader.group(2)),
                        Integer.parseInt(hunkHeader.group(3)),
                        parseCount(hunkHeader.group(4)));
                continue;
            }

            if (state.inHunk()) {
                if (line.startsWith("\\")) {
                    // "\ No newline at end of file" annotates the previous line; it is not a line.
                    continue;
                }
                if (line.startsWith("+")) {
                    state.addLine(DiffHunk.Kind.ADDED, line.substring(1));
                } else if (line.startsWith("-")) {
                    state.addLine(DiffHunk.Kind.REMOVED, line.substring(1));
                } else if (line.startsWith(" ")) {
                    state.addLine(DiffHunk.Kind.CONTEXT, line.substring(1));
                } else if (line.isEmpty()) {
                    // git emits a bare empty line for an empty context line.
                    state.addLine(DiffHunk.Kind.CONTEXT, "");
                } else {
                    // Anything else ends the hunk body (e.g. the next file's "index" header).
                    state.flushHunk();
                }
            }
        }

        state.flushInto(files);
        return DiffModel.of(files);
    }

    private static int parseCount(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * Removes git's {@code a/} and {@code b/} prefixes, and the timestamp some tools append after a
     * tab.
     */
    private static String stripPathPrefix(String path) {
        String p = path;
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        p = p.trim();
        if (p.equals(FileDiff.DEV_NULL)) {
            return p;
        }
        if (p.startsWith("a/") || p.startsWith("b/")) {
            return p.substring(2);
        }
        return p;
    }

    /** Mutable accumulator; kept package-private and short-lived so the parser itself stays pure. */
    private static final class State {
        private String oldPath;
        private String newPath;
        private final List<DiffHunk> hunks = new ArrayList<>();

        private int hunkOldStart;
        private int hunkOldCount;
        private int hunkNewStart;
        private int hunkNewCount;
        private List<DiffHunk.Line> hunkLines;

        void startFile(String oldPath, String newPath) {
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.hunks.clear();
        }

        boolean inHunk() {
            return hunkLines != null;
        }

        boolean hasCompleteFile() {
            return oldPath != null && newPath != null;
        }

        void beginHunk(int oldStart, int oldCount, int newStart, int newCount) {
            this.hunkOldStart = oldStart;
            this.hunkOldCount = oldCount;
            this.hunkNewStart = newStart;
            this.hunkNewCount = newCount;
            this.hunkLines = new ArrayList<>();
        }

        void addLine(DiffHunk.Kind kind, String content) {
            hunkLines.add(new DiffHunk.Line(kind, content));
        }

        void flushHunk() {
            if (hunkLines != null) {
                hunks.add(new DiffHunk(hunkOldStart, hunkOldCount, hunkNewStart, hunkNewCount, hunkLines));
                hunkLines = null;
            }
        }

        void flushInto(List<FileDiff> files) {
            flushHunk();
            if (oldPath != null && newPath != null) {
                files.add(new FileDiff(oldPath, newPath, List.copyOf(hunks)));
            }
            oldPath = null;
            newPath = null;
            hunks.clear();
        }
    }
}
