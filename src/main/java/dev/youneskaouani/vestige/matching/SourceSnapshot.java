package dev.youneskaouani.vestige.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of the files of one commit, used to compute the content-derived fingerprints.
 *
 * <p>Vestige has no working copy, so the content has to come with the report. SARIF 2.1.0 already
 * has a place for it — {@code run.artifacts[].contents.text} — and analysers can be configured to
 * embed it. When the report does not carry contents, {@link #empty()} is used and passes 3 and 4
 * simply find nothing: the matcher stays correct, just less precise. Making this an interface keeps
 * the door open for a future SCM-backed implementation without touching the matcher.
 */
public interface SourceSnapshot {

    /** The lines of a file, without line terminators, or empty when the file is not available. */
    Optional<List<String>> lines(String path);

    /** A snapshot that knows about no files at all. */
    static SourceSnapshot empty() {
        return path -> Optional.empty();
    }

    /** A snapshot backed by whole-file text, split into lines on any of CRLF, CR or LF. */
    static SourceSnapshot ofFileContents(Map<String, String> contentsByPath) {
        Map<String, List<String>> indexed = new LinkedHashMap<>();
        contentsByPath.forEach((path, text) ->
                indexed.put(TextNormalizer.normalisePath(path), splitLines(text)));
        Map<String, List<String>> frozen = Map.copyOf(indexed);
        return path -> Optional.ofNullable(frozen.get(TextNormalizer.normalisePath(path)));
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return List.copyOf(lines);
    }
}
