package dev.youneskaouani.vestige.matching;

import java.util.List;
import java.util.OptionalInt;

/**
 * One {@code @@ -oldStart,oldCount +newStart,newCount @@} hunk and the lines inside it.
 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {

    /** The kind of a line inside a hunk. */
    public enum Kind {
        /** Present in both sides. */
        CONTEXT,
        /** Present only in the head commit. */
        ADDED,
        /** Present only in the base commit. */
        REMOVED
    }

    /** A single line of hunk body, with its leading marker already stripped. */
    public record Line(Kind kind, String content) {
    }

    public DiffHunk {
        lines = List.copyOf(lines);
    }

    /** Last old-side line number covered by this hunk, or {@code oldStart - 1} for pure insertions. */
    public int oldEnd() {
        return oldStart + oldCount - 1;
    }

    /**
     * Walks the hunk body to translate a base-commit line into head-commit coordinates.
     *
     * <p>This is the heart of pass 2. We advance two cursors in lockstep — one over the base side,
     * one over the head side — consuming the hunk body line by line: context advances both,
     * a removal advances only the base cursor, an addition only the head cursor. When the base
     * cursor reaches the line we are looking for, the head cursor is by construction sitting on its
     * translated position. If the line we are looking for is the one being removed, the translation
     * is undefined and we say so rather than guessing.
     *
     * @return the head-commit line, or empty if the line was deleted by this hunk
     */
    public OptionalInt translateWithin(int oldLine) {
        int oldCursor = oldStart;
        int newCursor = newStart;
        for (Line line : lines) {
            switch (line.kind()) {
                case CONTEXT -> {
                    if (oldCursor == oldLine) {
                        return OptionalInt.of(newCursor);
                    }
                    oldCursor++;
                    newCursor++;
                }
                case REMOVED -> {
                    if (oldCursor == oldLine) {
                        return OptionalInt.empty();
                    }
                    oldCursor++;
                }
                case ADDED -> newCursor++;
            }
        }
        return OptionalInt.empty();
    }

    /** Head-commit line numbers this hunk introduces or rewrites. */
    public void collectAddedLines(java.util.Collection<Integer> sink) {
        int newCursor = newStart;
        for (Line line : lines) {
            switch (line.kind()) {
                case CONTEXT -> newCursor++;
                case ADDED -> sink.add(newCursor++);
                case REMOVED -> {
                    // Removed lines have no head-commit coordinate.
                }
            }
        }
    }
}
