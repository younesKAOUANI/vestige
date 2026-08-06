package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("returns an empty model for null or blank input")
        void handlesAbsentDiff() {
            assertThat(UnifiedDiffParser.parse(null).isEmpty()).isTrue();
            assertThat(UnifiedDiffParser.parse("   \n  ").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("reads paths, hunk headers and line kinds")
        void readsStructure() {
            String diff = """
                    diff --git a/src/Main.java b/src/Main.java
                    index 1234567..89abcde 100644
                    --- a/src/Main.java
                    +++ b/src/Main.java
                    @@ -10,4 +10,5 @@ class Main {
                         int a = 1;
                    -    int b = 2;
                    +    int b = 3;
                    +    int c = 4;
                         int d = 5;
                         int e = 6;
                    """;

            FileDiff file = UnifiedDiffParser.parse(diff).forOldPath("src/Main.java").orElseThrow();
            assertThat(file.newPath()).isEqualTo("src/Main.java");
            assertThat(file.isRename()).isFalse();
            assertThat(file.hunks()).hasSize(1);

            DiffHunk hunk = file.hunks().get(0);
            assertThat(hunk.oldStart()).isEqualTo(10);
            assertThat(hunk.oldCount()).isEqualTo(4);
            assertThat(hunk.newStart()).isEqualTo(10);
            assertThat(hunk.newCount()).isEqualTo(5);
            assertThat(hunk.lines()).extracting(DiffHunk.Line::kind)
                    .containsExactly(
                            DiffHunk.Kind.CONTEXT,
                            DiffHunk.Kind.REMOVED,
                            DiffHunk.Kind.ADDED,
                            DiffHunk.Kind.ADDED,
                            DiffHunk.Kind.CONTEXT,
                            DiffHunk.Kind.CONTEXT);
        }

        @Test
        @DisplayName("defaults an omitted hunk count to one, as the format allows")
        void defaultsOmittedCount() {
            String diff = """
                    --- a/f.txt
                    +++ b/f.txt
                    @@ -7 +7 @@
                    -old
                    +new
                    """;
            DiffHunk hunk = UnifiedDiffParser.parse(diff).forOldPath("f.txt").orElseThrow().hunks().get(0);
            assertThat(hunk.oldCount()).isEqualTo(1);
            assertThat(hunk.newCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("recognises renames from both the git header and the rename directives")
        void recognisesRenames() {
            String diff = """
                    diff --git a/src/Old.java b/src/New.java
                    similarity index 96%
                    rename from src/Old.java
                    rename to src/New.java
                    --- a/src/Old.java
                    +++ b/src/New.java
                    @@ -1,2 +1,2 @@
                    -class Old {}
                    +class New {}
                     // trailing
                    """;
            FileDiff file = UnifiedDiffParser.parse(diff).forOldPath("src/Old.java").orElseThrow();
            assertThat(file.isRename()).isTrue();
            assertThat(file.newPath()).isEqualTo("src/New.java");
        }

        @Test
        @DisplayName("recognises additions and deletions through /dev/null")
        void recognisesAddAndDelete() {
            String added = """
                    diff --git a/src/New.java b/src/New.java
                    new file mode 100644
                    --- /dev/null
                    +++ b/src/New.java
                    @@ -0,0 +1,2 @@
                    +class New {}
                    +
                    """;
            DiffModel model = UnifiedDiffParser.parse(added);
            assertThat(model.changedLines("src/New.java")).containsExactly(1, 2);

            String deleted = """
                    diff --git a/src/Gone.java b/src/Gone.java
                    deleted file mode 100644
                    --- a/src/Gone.java
                    +++ /dev/null
                    @@ -1,2 +0,0 @@
                    -class Gone {}
                    -
                    """;
            FileDiff file = UnifiedDiffParser.parse(deleted).forOldPath("src/Gone.java").orElseThrow();
            assertThat(file.isDeleted()).isTrue();
            assertThat(file.translateLine(1)).isEmpty();
        }

        @Test
        @DisplayName("separates files in plain diff -u output that has no git headers")
        void separatesFilesWithoutGitHeaders() {
            String diff = """
                    --- a/one.txt
                    +++ b/one.txt
                    @@ -1,1 +1,1 @@
                    -a
                    +b
                    --- a/two.txt
                    +++ b/two.txt
                    @@ -5,1 +5,1 @@
                    -c
                    +d
                    """;
            DiffModel model = UnifiedDiffParser.parse(diff);
            assertThat(model.changedOldPaths()).containsExactlyInAnyOrder("one.txt", "two.txt");
            assertThat(model.forOldPath("one.txt").orElseThrow().hunks()).hasSize(1);
            assertThat(model.forOldPath("two.txt").orElseThrow().hunks()).hasSize(1);
        }

        @Test
        @DisplayName("ignores the no-newline-at-end-of-file marker")
        void ignoresNoNewlineMarker() {
            String diff = """
                    --- a/f.txt
                    +++ b/f.txt
                    @@ -1,1 +1,1 @@
                    -a
                    \\ No newline at end of file
                    +b
                    """;
            DiffHunk hunk = UnifiedDiffParser.parse(diff).forOldPath("f.txt").orElseThrow().hunks().get(0);
            assertThat(hunk.lines()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("line translation")
    class LineTranslation {

        @Test
        @DisplayName("shifts lines after an insertion and leaves earlier lines alone")
        void shiftsAfterInsertion() {
            List<String> before = List.of("a", "b", "c", "d", "e", "f", "g", "h");
            List<String> after = List.of("a", "x", "y", "b", "c", "d", "e", "f", "g", "h");
            DiffModel model = UnifiedDiffParser.parse(TestUnifiedDiff.between("f.txt", before, "f.txt", after));

            assertThat(model.translateLine("f.txt", 1)).hasValue(1);
            assertThat(model.translateLine("f.txt", 2)).hasValue(4);
            assertThat(model.translateLine("f.txt", 8)).hasValue(10);
        }

        @Test
        @DisplayName("shifts lines back after a deletion and reports deleted lines as gone")
        void shiftsAfterDeletion() {
            List<String> before = List.of("a", "b", "c", "d", "e", "f", "g", "h");
            List<String> after = List.of("a", "d", "e", "f", "g", "h");
            DiffModel model = UnifiedDiffParser.parse(TestUnifiedDiff.between("f.txt", before, "f.txt", after));

            assertThat(model.translateLine("f.txt", 1)).hasValue(1);
            assertThat(model.translateLine("f.txt", 2)).isEmpty();
            assertThat(model.translateLine("f.txt", 3)).isEmpty();
            assertThat(model.translateLine("f.txt", 4)).hasValue(2);
            assertThat(model.translateLine("f.txt", 8)).hasValue(6);
        }

        @Test
        @DisplayName("carries the offset across several hunks")
        void accumulatesAcrossHunks() {
            List<String> before = List.of(
                    "l01", "l02", "l03", "l04", "l05", "l06", "l07", "l08", "l09", "l10",
                    "l11", "l12", "l13", "l14", "l15", "l16", "l17", "l18", "l19", "l20",
                    "l21", "l22", "l23", "l24", "l25", "l26", "l27", "l28", "l29", "l30");
            List<String> after = new java.util.ArrayList<>(before);
            after.add(24, "inserted-late");
            after.add(4, "inserted-early-b");
            after.add(4, "inserted-early-a");

            DiffModel model = UnifiedDiffParser.parse(TestUnifiedDiff.between("f.txt", before, "f.txt", after));

            assertThat(model.translateLine("f.txt", 3)).hasValue(3);
            assertThat(model.translateLine("f.txt", 6)).hasValue(8);
            assertThat(model.translateLine("f.txt", 20)).hasValue(22);
            assertThat(model.translateLine("f.txt", 30)).hasValue(33);
        }

        @Test
        @DisplayName("treats an unmentioned file as unchanged")
        void leavesUnmentionedFilesAlone() {
            DiffModel model = UnifiedDiffParser.parse("""
                    --- a/one.txt
                    +++ b/one.txt
                    @@ -1,1 +1,2 @@
                     a
                    +b
                    """);
            assertThat(model.translateLine("other.txt", 42)).hasValue(42);
            assertThat(model.mapPath("other.txt")).isEqualTo("other.txt");
        }

        @Test
        @DisplayName("maps the path of a renamed file")
        void mapsRenamedPath() {
            DiffModel model = UnifiedDiffParser.parse("""
                    diff --git a/src/Old.java b/src/New.java
                    rename from src/Old.java
                    rename to src/New.java
                    """);
            assertThat(model.mapPath("src/Old.java")).isEqualTo("src/New.java");
            assertThat(model.translateLine("src/Old.java", 12)).hasValue(12);
        }

        @Test
        @DisplayName("agrees with a brute-force replay of the edit script for a random edit")
        void agreesWithBruteForce() {
            List<String> before = new java.util.ArrayList<>();
            for (int i = 1; i <= 60; i++) {
                before.add("line-" + i);
            }
            List<String> after = new java.util.ArrayList<>(before);
            after.remove(41);
            after.remove(40);
            after.add(30, "new-c");
            after.add(12, "new-b");
            after.add(3, "new-a");

            DiffModel model = UnifiedDiffParser.parse(TestUnifiedDiff.between("f.txt", before, "f.txt", after));

            for (int oldLine = 1; oldLine <= before.size(); oldLine++) {
                String text = before.get(oldLine - 1);
                int expected = after.indexOf(text) + 1;
                if (expected == 0) {
                    assertThat(model.translateLine("f.txt", oldLine)).isEmpty();
                } else {
                    assertThat(model.translateLine("f.txt", oldLine)).hasValue(expected);
                }
            }
        }
    }

    @Nested
    @DisplayName("changed lines")
    class ChangedLines {

        @Test
        @DisplayName("reports the head-commit lines that were added or rewritten")
        void reportsAddedLines() {
            List<String> before = List.of("a", "b", "c", "d", "e", "f");
            List<String> after = List.of("a", "b", "C", "d", "e", "f", "g");
            DiffModel model = UnifiedDiffParser.parse(TestUnifiedDiff.between("f.txt", before, "f.txt", after));

            assertThat(model.changedLines("f.txt")).containsExactly(3, 7);
            assertThat(model.isChangedLine("f.txt", 3)).isTrue();
            assertThat(model.isChangedLine("f.txt", 4)).isFalse();
            assertThat(model.changedLines("untouched.txt")).isEmpty();
        }
    }
}
