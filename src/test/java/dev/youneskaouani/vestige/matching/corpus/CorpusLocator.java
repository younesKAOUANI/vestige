package dev.youneskaouani.vestige.matching.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds {@code matcher-corpus/cases} without hard-coding a path that only works from one working
 * directory. Maven runs tests with the module's own directory as the working directory, which is
 * where matcher-corpus/ lives too, so the common case is a single check; the upward walk is what
 * keeps this working from an IDE runner or a subdirectory as well.
 */
public final class CorpusLocator {

    private CorpusLocator() {}

    public static Path locateCasesDirectory() {
        Path start = Path.of("").toAbsolutePath();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path attempt = candidate.resolve("matcher-corpus").resolve("cases");
            if (Files.isDirectory(attempt)) {
                return attempt;
            }
        }
        throw new IllegalStateException(
                "Could not locate matcher-corpus/cases starting from working directory " + start);
    }

    static java.util.List<Path> listCaseFiles(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
