package dev.youneskaouani.vestige.matching.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.matching.FingerprintFactory;
import dev.youneskaouani.vestige.matching.Fingerprints;
import dev.youneskaouani.vestige.matching.IncomingFinding;
import dev.youneskaouani.vestige.matching.IssueMatcher;
import dev.youneskaouani.vestige.matching.Match;
import dev.youneskaouani.vestige.matching.MatchResult;
import dev.youneskaouani.vestige.matching.PreviousIssueCandidate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs every {@link CorpusCase} through the real {@link IssueMatcher} - no mock, no reimplemented
 * copy of the algorithm - and scores the result against each case's hand-authored ground truth.
 *
 * <p>Vocabulary, matching the architecture doc's acceptance bar for the matcher (§3.3):
 *
 * <ul>
 *   <li><b>false split</b>: an {@code expectedMatches} pairing the corpus asserts, that the matcher
 *       did not produce. The same real-world issue would be tracked as two unrelated ones.
 *   <li><b>false merge</b>: a pairing the matcher produced that is not in {@code expectedMatches} -
 *       either a previous issue matched to the wrong current finding, or two genuinely different
 *       issues collapsed into one.
 * </ul>
 *
 * <p>Rates are defined against two different denominators, deliberately: {@code falseSplitRate =
 * falseSplits / totalExpectedMatches} (of everything that should have matched, how much didn't),
 * {@code falseMergeRate = falseMerges / totalActualMatches} (of everything the matcher claimed, how
 * much was wrong). A case that expects zero matches (e.g. two genuinely unrelated findings)
 * contributes nothing to the split rate's denominator but still penalises a wrong merge if the
 * matcher pairs them anyway.
 *
 * <p>This class has no JUnit dependency, so it can be driven from a plain {@code main} method or a
 * notebook as well as from {@link MatcherCorpusHarnessTest}, which is the thing that turns a {@link
 * Report} into a pass/fail build gate.
 */
public final class MatcherCorpusHarness {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int weakLineProximity;

    public MatcherCorpusHarness(int weakLineProximity) {
        this.weakLineProximity = weakLineProximity;
    }

    /**
     * Parses every {@code *.json} file in {@code directory} and validates each case's internal
     * references.
     */
    public List<CorpusCase> loadCases(Path directory) {
        List<CorpusCase> cases = new ArrayList<>();
        for (Path file : CorpusLocator.listCaseFiles(directory)) {
            try {
                CorpusCase corpusCase = objectMapper.readValue(file.toFile(), CorpusCase.class);
                corpusCase.validate();
                cases.add(corpusCase);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to parse matcher-corpus fixture " + file, e);
            }
        }
        return cases;
    }

    /**
     * Feeds every case's {@code before}/{@code after} findings through {@link IssueMatcher},
     * applying each case's {@code renames} map to the previous side exactly as {@code
     * IssueMatchingService} would (see the field-by-field walkthrough on {@link CorpusCase}), and
     * scores the result.
     */
    public Report evaluate(List<CorpusCase> cases) {
        IssueMatcher matcher = new IssueMatcher(weakLineProximity);

        int totalExpectedMatches = 0;
        int totalActualMatches = 0;
        int correctMatches = 0;
        int falseSplits = 0;
        int falseMerges = 0;
        List<String> failures = new ArrayList<>();

        for (CorpusCase corpusCase : cases) {
            CaseInputs inputs = buildInputs(corpusCase);
            MatchResult result = matcher.match(inputs.previous(), inputs.current());

            Set<String> expected = new HashSet<>();
            for (ExpectedMatch expectedMatch : corpusCase.expectedMatches()) {
                expected.add(pairingKey(expectedMatch.before(), expectedMatch.after()));
            }

            Set<String> actual = new HashSet<>();
            for (Match match : result.matches()) {
                String beforeId = inputs.issueIdToBeforeId().get(match.previous().issueId());
                String afterId = inputs.ordinalToAfterId().get(match.current().ordinal());
                actual.add(pairingKey(beforeId, afterId));
            }

            totalExpectedMatches += expected.size();
            totalActualMatches += actual.size();

            for (String pairing : actual) {
                if (expected.contains(pairing)) {
                    correctMatches++;
                } else {
                    falseMerges++;
                    failures.add(
                            corpusCase.id()
                                    + ": false merge - matcher produced "
                                    + pairing
                                    + ", which the fixture does not expect");
                }
            }
            for (String pairing : expected) {
                if (!actual.contains(pairing)) {
                    falseSplits++;
                    failures.add(
                            corpusCase.id()
                                    + ": false split - matcher failed to produce expected "
                                    + pairing);
                }
            }
        }

        double falseSplitRate =
                totalExpectedMatches == 0 ? 0.0 : (double) falseSplits / totalExpectedMatches;
        double falseMergeRate =
                totalActualMatches == 0 ? 0.0 : (double) falseMerges / totalActualMatches;

        return new Report(
                cases.size(),
                totalExpectedMatches,
                totalActualMatches,
                correctMatches,
                falseSplits,
                falseMerges,
                falseSplitRate,
                falseMergeRate,
                List.copyOf(failures));
    }

    private CaseInputs buildInputs(CorpusCase corpusCase) {
        Map<String, UUID> beforeIdToIssueId = new HashMap<>();
        Map<UUID, String> issueIdToBeforeId = new HashMap<>();
        List<PreviousIssueCandidate> previous = new ArrayList<>();

        List<CorpusFinding> beforeFindings = corpusCase.before();
        for (int i = 0; i < beforeFindings.size(); i++) {
            CorpusFinding finding = beforeFindings.get(i);
            // Deterministic, collision-free within one case: the fixture's own before-id is unique
            // per case (validate() enforces it) and this harness never compares ids across cases.
            UUID issueId =
                    UUID.nameUUIDFromBytes(
                            (corpusCase.id() + "/before/" + finding.id())
                                    .getBytes(StandardCharsets.UTF_8));
            beforeIdToIssueId.put(finding.id(), issueId);
            issueIdToBeforeId.put(issueId, finding.id());

            // §3.3: the SCM rename map is applied to the previous candidate's path before its
            // fingerprint is recomputed for this run, not the other way around - the after side
            // is already scanned at wherever the file lives now.
            String renamedPath =
                    corpusCase.renames().getOrDefault(finding.filePath(), finding.filePath());
            Fingerprints fingerprints =
                    FingerprintFactory.compute(
                            finding.ruleId(), renamedPath, finding.symbolPath(), finding.snippet());
            previous.add(new PreviousIssueCandidate(issueId, i, finding.line(), fingerprints));
        }

        Map<Integer, String> ordinalToAfterId = new HashMap<>();
        List<IncomingFinding> current = new ArrayList<>();
        List<CorpusFinding> afterFindings = corpusCase.after();
        for (int i = 0; i < afterFindings.size(); i++) {
            CorpusFinding finding = afterFindings.get(i);
            ordinalToAfterId.put(i, finding.id());
            Fingerprints fingerprints =
                    FingerprintFactory.compute(
                            finding.ruleId(),
                            finding.filePath(),
                            finding.symbolPath(),
                            finding.snippet());
            current.add(new IncomingFinding(i, finding.line(), fingerprints));
        }

        return new CaseInputs(previous, current, issueIdToBeforeId, ordinalToAfterId);
    }

    private static String pairingKey(String beforeId, String afterId) {
        return beforeId + "->" + afterId;
    }

    private record CaseInputs(
            List<PreviousIssueCandidate> previous,
            List<IncomingFinding> current,
            Map<UUID, String> issueIdToBeforeId,
            Map<Integer, String> ordinalToAfterId) {}

    /**
     * @param falseSplitRate {@code falseSplits / totalExpectedMatches}, 0.0 if no matches were
     *     expected
     * @param falseMergeRate {@code falseMerges / totalActualMatches}, 0.0 if the matcher produced
     *     nothing
     * @param failures one human-readable line per false split/merge, for build-log diagnosis
     */
    public record Report(
            int caseCount,
            int totalExpectedMatches,
            int totalActualMatches,
            int correctMatches,
            int falseSplits,
            int falseMerges,
            double falseSplitRate,
            double falseMergeRate,
            List<String> failures) {

        public String summary() {
            return String.format(
                    "matcher-corpus: %d cases, %d expected matches, %d actual matches, %d correct, "
                            + "%d false splits (%.2f%%), %d false merges (%.2f%%)",
                    caseCount,
                    totalExpectedMatches,
                    totalActualMatches,
                    correctMatches,
                    falseSplits,
                    falseSplitRate * 100.0,
                    falseMerges,
                    falseMergeRate * 100.0);
        }
    }
}
