package dev.youneskaouani.vestige.matching;

/**
 * Normalises a file path so that two analysers - or the same analyser run from a different working
 * directory - report the same file under the same string (§3.2's {@code normalised_file_path}).
 *
 * <p>Deliberately narrow: it collapses the punctuation differences a path can pick up in transit
 * (backslashes, doubled slashes, {@code ./} segments, a leading or trailing slash) without touching
 * case. Case is left alone on purpose - most of the file systems Vestige's targets run on are
 * case-sensitive, and folding case would silently merge two distinct files on any of them.
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    public static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        String[] segments = rawPath.replace('\\', '/').split("/");
        StringBuilder out = new StringBuilder(rawPath.length());
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }
}
