package dev.youneskaouani.vestige.issues.service;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.common.domain.IssueLifecycle;
import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.gate.domain.GateInput;
import dev.youneskaouani.vestige.github.service.ScmRenameResolver;
import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.domain.FindingRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.issues.domain.MatchRung;
import dev.youneskaouani.vestige.matching.FingerprintFactory;
import dev.youneskaouani.vestige.matching.Fingerprints;
import dev.youneskaouani.vestige.matching.IncomingFinding;
import dev.youneskaouani.vestige.matching.IssueMatcher;
import dev.youneskaouani.vestige.matching.Match;
import dev.youneskaouani.vestige.matching.MatchResult;
import dev.youneskaouani.vestige.matching.PreviousIssueCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bridges the pure {@link IssueMatcher} to persistence: loads the branch's previously-tracked
 * issues, applies the commit's rename map, runs §3.3's algorithm, and applies every outcome -
 * attach, open, or auto-resolve - to the {@link Issue}/{@link Finding} rows. This is
 * {@code RunProcessingService}'s single call into the matching+issues boundary; everything else
 * about how a run is orchestrated lives on that side.
 *
 * <p>Must run inside the same transaction as the run's own persistence (§4.1: the run row, its
 * findings, and every issue mutation commit together or not at all).
 */
@Service
public class IssueTrackingService {

    private final IssueRepository issueRepository;
    private final FindingRepository findingRepository;
    private final ScmRenameResolver renameResolver;
    private final VestigeProperties properties;

    public IssueTrackingService(
            IssueRepository issueRepository,
            FindingRepository findingRepository,
            ScmRenameResolver renameResolver,
            VestigeProperties properties) {
        this.issueRepository = issueRepository;
        this.findingRepository = findingRepository;
        this.renameResolver = renameResolver;
        this.properties = properties;
    }

    /**
     * @param currentFindings this run's findings, already persisted (issue_id still null) - the
     *     same managed JPA instances the caller inserted, so mutating them here is picked up by
     *     Hibernate's dirty checking without a further explicit save
     */
    public TrackingResult track(
            UUID organizationId,
            UUID projectId,
            UUID branchId,
            UUID runId,
            String commitSha,
            String baseCommitSha,
            String provider,
            String owner,
            String repo,
            List<Finding> currentFindings,
            Instant now) {

        List<Issue> previousIssues = issueRepository.findAllByBranchId(branchId);
        Map<String, String> renames = resolveRenames(provider, owner, repo, baseCommitSha, commitSha);

        Map<UUID, Issue> issueById = new HashMap<>();
        List<PreviousIssueCandidate> previousCandidates = new ArrayList<>(previousIssues.size());
        for (Issue issue : previousIssues) {
            issueById.put(issue.getId(), issue);
            previousCandidates.add(candidateFor(issue, renames));
        }

        List<IncomingFinding> incoming = new ArrayList<>(currentFindings.size());
        for (int ordinal = 0; ordinal < currentFindings.size(); ordinal++) {
            Finding finding = currentFindings.get(ordinal);
            Fingerprints fingerprints =
                    new Fingerprints(finding.getIdentityFp(), finding.getContextFp(), finding.getWeakFp());
            incoming.add(new IncomingFinding(ordinal, finding.getStartLine(), fingerprints));
        }

        IssueMatcher matcher = new IssueMatcher(properties.matching().weakFingerprintLineProximity());
        MatchResult result = matcher.match(previousCandidates, incoming);

        List<GateInput.GateIssue> gateIssues = new ArrayList<>();
        int reopenedCount = 0;
        for (Match match : result.matches()) {
            Issue issue = issueById.get(match.previous().issueId());
            Finding finding = currentFindings.get(match.current().ordinal());
            IssueStatus before = issue.getStatus();

            issue.recordSighting(
                    finding.getRuleId(),
                    finding.getSeverity(),
                    finding.getMessage(),
                    finding.getFilePath(),
                    finding.getSymbolPath(),
                    finding.getStartLine(),
                    runId,
                    now);
            finding.assignToIssue(issue.getId(), MatchRung.from(match.rung()));

            boolean reopened = IssueLifecycle.isReopening(before, issue.getStatus());
            if (reopened) {
                reopenedCount++;
            }
            gateIssues.add(new GateInput.GateIssue(
                    issue.getId().toString(), issue.getSeverity(), issue.getStatus(), false, reopened));
        }

        int newIssueCount = 0;
        for (IncomingFinding newFinding : result.newIssues()) {
            Finding finding = currentFindings.get(newFinding.ordinal());
            Issue issue = new Issue(
                    UUID.randomUUID(),
                    organizationId,
                    projectId,
                    branchId,
                    finding.getRuleId(),
                    finding.getSeverity(),
                    finding.getMessage(),
                    finding.getFilePath(),
                    finding.getSymbolPath(),
                    finding.getStartLine(),
                    runId,
                    commitSha,
                    now);
            issueRepository.save(issue);
            finding.assignToIssue(issue.getId(), MatchRung.NEW);
            newIssueCount++;
            gateIssues.add(new GateInput.GateIssue(
                    issue.getId().toString(), issue.getSeverity(), issue.getStatus(), true, false));
        }

        int autoResolvedCount = 0;
        for (PreviousIssueCandidate stillMissing : result.noLongerPresent()) {
            Issue issue = issueById.get(stillMissing.issueId());
            IssueStatus before = issue.getStatus();
            issue.recordDisappearance(now);
            if (issue.getStatus() != before) {
                autoResolvedCount++;
            }
        }

        return new TrackingResult(
                gateIssues, newIssueCount, result.matches().size(), reopenedCount, autoResolvedCount);
    }

    /** Only ever asks a real provider for renames when the project is actually hosted there - see §11. */
    private Map<String, String> resolveRenames(
            String provider, String owner, String repo, String baseCommitSha, String commitSha) {
        if (!"github".equalsIgnoreCase(provider) || owner == null || owner.isBlank()) {
            return Map.of();
        }
        return renameResolver.renamesBetween(owner, repo, baseCommitSha, commitSha);
    }

    /**
     * Builds a {@code PreviousIssueCandidate} from an issue's most recent sighting, recomputing its
     * fingerprints over the <em>renamed</em> path (§3.2) - the stored {@code finding.identity_fp}
     * etc. reflect the path as of that finding's own run, not this one, so reusing them directly
     * would silently break every renamed file's matching (see {@code FingerprintFactory}'s javadoc).
     */
    private PreviousIssueCandidate candidateFor(Issue issue, Map<String, String> renames) {
        Finding lastFinding = findingRepository
                .findByIssueIdAndAnalysisRunId(issue.getId(), issue.getLastSeenRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "Issue " + issue.getId() + " has no finding for its own last_seen_run_id "
                                + issue.getLastSeenRunId() + " - the two must always be written together"));

        String renamedPath = renames.getOrDefault(lastFinding.getFilePath(), lastFinding.getFilePath());
        Fingerprints fingerprints = FingerprintFactory.compute(
                lastFinding.getRuleId(), renamedPath, lastFinding.getSymbolPath(), lastFinding.getLineSnippet());
        return new PreviousIssueCandidate(
                issue.getId(), lastFinding.getSeq(), lastFinding.getStartLine(), fingerprints);
    }
}
