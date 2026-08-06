package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces genuine {@code git}-shaped unified diffs from a before/after pair.
 *
 * <p>The matcher's tests must not be allowed to mark their own homework: hand-written hunk headers
 * would be tuned, consciously or not, to whatever {@link UnifiedDiffParser} happens to do. This
 * writer derives the edit script with a longest-common-subsequence pass and emits it in the format
 * git emits — three lines of context, merged hunks, {@code -l,0} headers for pure insertions — so
 * the parser and the line translator are exercised against diffs nobody hand-tailored.
 */
final class TestUnifiedDiff {

    private static final int CONTEXT = 3;

    private enum Op {
        EQUAL,
        DELETE,
        INSERT
    }

    private record Entry(Op op, String text, int oldNo, int newNo) {
    }

    private TestUnifiedDiff() {
    }

    /** A unified diff between two versions of one file. */
    static String between(String oldPath, List<String> oldLines, String newPath, List<String> newLines) {
        List<Entry> script = editScript(oldLines, newLines);
        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append('\n');
        if (!oldPath.equals(newPath)) {
            out.append("similarity index 95%\n");
            out.append("rename from ").append(oldPath).append('\n');
            out.append("rename to ").append(newPath).append('\n');
        }
        List<int[]> ranges = hunkRanges(script);
        if (ranges.isEmpty()) {
            return out.toString();
        }
        out.append("--- a/").append(oldPath).append('\n');
        out.append("+++ b/").append(newPath).append('\n');
        for (int[] range : ranges) {
            appendHunk(out, script, range[0], range[1]);
        }
        return out.toString();
    }

    private static void appendHunk(StringBuilder out, List<Entry> script, int from, int to) {
        int oldCount = 0;
        int newCount = 0;
        int oldStart = 0;
        int newStart = 0;
        for (int i = from; i <= to; i++) {
            Entry entry = script.get(i);
            if (entry.op() != Op.INSERT) {
                oldCount++;
                if (oldStart == 0) {
                    oldStart = entry.oldNo();
                }
            }
            if (entry.op() != Op.DELETE) {
                newCount++;
                if (newStart == 0) {
                    newStart = entry.newNo();
                }
            }
        }
        if (oldCount == 0) {
            oldStart = oldLinesBefore(script, from);
        }
        if (newCount == 0) {
            newStart = newLinesBefore(script, from);
        }

        out.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
        for (int i = from; i <= to; i++) {
            Entry entry = script.get(i);
            char marker = switch (entry.op()) {
                case EQUAL -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
            out.append(marker).append(entry.text()).append('\n');
        }
    }

    private static int oldLinesBefore(List<Entry> script, int index) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (script.get(i).op() != Op.INSERT) {
                count++;
            }
        }
        return count;
    }

    private static int newLinesBefore(List<Entry> script, int index) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (script.get(i).op() != Op.DELETE) {
                count++;
            }
        }
        return count;
    }

    /** Index ranges of the script that make up hunks, with context added and overlaps merged. */
    private static List<int[]> hunkRanges(List<Entry> script) {
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < script.size(); i++) {
            if (script.get(i).op() != Op.EQUAL) {
                changes.add(i);
            }
        }
        List<int[]> ranges = new ArrayList<>();
        int index = 0;
        while (index < changes.size()) {
            int first = changes.get(index);
            int last = first;
            int lookahead = index + 1;
            while (lookahead < changes.size() && changes.get(lookahead) - last <= 2 * CONTEXT) {
                last = changes.get(lookahead);
                lookahead++;
            }
            ranges.add(new int[] {
                Math.max(0, first - CONTEXT), Math.min(script.size() - 1, last + CONTEXT)
            });
            index = lookahead;
        }
        return ranges;
    }

    /** Classic dynamic-programming LCS, then a backtrack into an edit script. */
    private static List<Entry> editScript(List<String> oldLines, List<String> newLines) {
        int m = oldLines.size();
        int n = newLines.size();
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Entry> script = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                script.add(new Entry(Op.EQUAL, oldLines.get(i), i + 1, j + 1));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                script.add(new Entry(Op.DELETE, oldLines.get(i), i + 1, 0));
                i++;
            } else {
                script.add(new Entry(Op.INSERT, newLines.get(j), 0, j + 1));
                j++;
            }
        }
        while (i < m) {
            script.add(new Entry(Op.DELETE, oldLines.get(i), i + 1, 0));
            i++;
        }
        while (j < n) {
            script.add(new Entry(Op.INSERT, newLines.get(j), 0, j + 1));
            j++;
        }
        return script;
    }
}
