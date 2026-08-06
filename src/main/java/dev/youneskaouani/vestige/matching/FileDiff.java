package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/**
 * The diff of a single file between the base and the head commit.
 *
 * <p>{@code oldPath} and {@code newPath} differ when the file was renamed; either may be
 * {@link #DEV_NULL} for a file that was added or deleted outright.
 */
public record FileDiff(String oldPath, String newPath, List<DiffHunk> hunks) {

    /** Git's placeholder for "this side does not exist". */
    public static final String DEV_NULL = "/dev/null";

    public FileDiff {
        oldPath = TextNormalizer.normalisePath(oldPath);
        newPath = TextNormalizer.normalisePath(newPath);
        List<DiffHunk> sorted = new ArrayList<>(hunks);
        sorted.sort(Comparator.comparingInt(DiffHunk::oldStart).thenComparingInt(DiffHunk::newStart));
        hunks = List.copyOf(sorted);
    }

    public boolean isRename() {
        return !oldPath.equals(newPath) && !isAdded() && !isDeleted();
    }

    public boolean isAdded() {
        return DEV_NULL.equals(oldPath) || "dev/null".equals(oldPath);
    }

    public boolean isDeleted() {
        return DEV_NULL.equals(newPath) || "dev/null".equals(newPath);
    }

    /**
     * Translates a base-commit line number into head-commit coordinates.
     *
     * <p>Hunks are sorted by base-commit position, so we can sweep them once while carrying the
     * running offset introduced by earlier hunks:
     *
     * <ul>
     *   <li>a line before the current hunk is only shifted by that accumulated offset;
     *   <li>a line inside the current hunk needs the line-by-line walk in
     *       {@link DiffHunk#translateWithin(int)};
     *   <li>otherwise absorb this hunk's net growth and carry on.
     * </ul>
     *
     * @return the head-commit line, or empty if the line no longer exists
     */
    public OptionalInt translateLine(int oldLine) {
        if (isDeleted()) {
            return OptionalInt.empty();
        }
        int offset = 0;
        for (DiffHunk hunk : hunks) {
            boolean pureInsertion = hunk.oldCount() == 0;
            if (oldLine < hunk.oldStart() || (pureInsertion && oldLine <= hunk.oldStart())) {
                return OptionalInt.of(oldLine + offset);
            }
            if (!pureInsertion && oldLine <= hunk.oldEnd()) {
                return hunk.translateWithin(oldLine);
            }
            offset += hunk.newCount() - hunk.oldCount();
        }
        return OptionalInt.of(oldLine + offset);
    }

    /** Head-commit line numbers that this diff added or rewrote. */
    public List<Integer> addedLines() {
        List<Integer> lines = new ArrayList<>();
        for (DiffHunk hunk : hunks) {
            hunk.collectAddedLines(lines);
        }
        return List.copyOf(lines);
    }
}
