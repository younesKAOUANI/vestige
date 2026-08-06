package dev.youneskaouani.vestige.matching;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The whole base&rarr;head diff, indexed for the two questions the pipeline asks of it: "where did
 * this line go?" and "which lines did this change touch?".
 *
 * <p>A diff is optional input. When CI does not supply one — a first analysis, a full-branch scan —
 * {@link #empty()} degrades gracefully: pass 2 finds nothing and the remaining passes carry the
 * matching. That is a deliberate design property, not an accident: the matcher must never *depend*
 * on the diff being available.
 */
public final class DiffModel {

    private static final DiffModel EMPTY = new DiffModel(List.of());

    private final Map<String, FileDiff> byOldPath;
    private final Map<String, FileDiff> byNewPath;

    private DiffModel(Collection<FileDiff> files) {
        Map<String, FileDiff> old = new LinkedHashMap<>();
        Map<String, FileDiff> fresh = new LinkedHashMap<>();
        for (FileDiff file : files) {
            if (!file.isAdded()) {
                old.put(file.oldPath(), file);
            }
            if (!file.isDeleted()) {
                fresh.put(file.newPath(), file);
            }
        }
        this.byOldPath = Map.copyOf(old);
        this.byNewPath = Map.copyOf(fresh);
    }

    public static DiffModel of(Collection<FileDiff> files) {
        return files.isEmpty() ? EMPTY : new DiffModel(files);
    }

    public static DiffModel empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return byOldPath.isEmpty() && byNewPath.isEmpty();
    }

    /** The files described by this diff, keyed by their base-commit path. */
    public Set<String> changedOldPaths() {
        return byOldPath.keySet();
    }

    /**
     * The head-commit path of a file that the base commit called {@code oldPath}.
     *
     * <p>Files the diff does not mention are unchanged, so they keep their path.
     */
    public String mapPath(String oldPath) {
        String normalised = TextNormalizer.normalisePath(oldPath);
        FileDiff file = byOldPath.get(normalised);
        return file == null ? normalised : file.newPath();
    }

    /**
     * Translates a base-commit line into head-commit coordinates.
     *
     * @return empty when the file was deleted or the line itself was removed
     */
    public OptionalInt translateLine(String oldPath, int oldLine) {
        FileDiff file = byOldPath.get(TextNormalizer.normalisePath(oldPath));
        if (file == null) {
            return OptionalInt.of(oldLine);
        }
        return file.translateLine(oldLine);
    }

    /** The head-commit lines this diff added or rewrote in {@code newPath}. */
    public Set<Integer> changedLines(String newPath) {
        FileDiff file = byNewPath.get(TextNormalizer.normalisePath(newPath));
        if (file == null) {
            return Set.of();
        }
        return new TreeSet<>(file.addedLines());
    }

    /** True when the given head-commit line was added or rewritten by this diff. */
    public boolean isChangedLine(String newPath, int newLine) {
        return changedLines(newPath).contains(newLine);
    }

    public Optional<FileDiff> forOldPath(String oldPath) {
        return Optional.ofNullable(byOldPath.get(TextNormalizer.normalisePath(oldPath)));
    }
}
