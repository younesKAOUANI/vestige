package dev.youneskaouani.vestige.ingestion.service;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.common.hash.Sha256;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJob;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJobRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisReportPayload;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisReportPayloadRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import dev.youneskaouani.vestige.tenancy.domain.Branch;
import dev.youneskaouani.vestige.tenancy.domain.BranchRepository;
import dev.youneskaouani.vestige.tenancy.domain.Project;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/v1/runs}'s core logic (§4.1): validate, resolve identity, and persist the run,
 * its uploaded bytes, and its outbox row as one atomic unit. Parsing the report body itself never
 * happens here - only {@link SarifReader#peekToolIdentity} runs on the request thread, which is the
 * whole reason that method exists (see its own javadoc). The rest of §4.2's pipeline is {@code
 * RunProcessingService}'s job, off this thread entirely.
 */
@Service
public class RunIngestionService {

    private final SarifReader sarifReader;
    private final ProjectRepository projectRepository;
    private final BranchRepository branchRepository;
    private final AnalysisRunRepository runRepository;
    private final AnalysisReportPayloadRepository payloadRepository;
    private final AnalysisJobRepository jobRepository;
    private final VestigeProperties properties;

    public RunIngestionService(
            SarifReader sarifReader,
            ProjectRepository projectRepository,
            BranchRepository branchRepository,
            AnalysisRunRepository runRepository,
            AnalysisReportPayloadRepository payloadRepository,
            AnalysisJobRepository jobRepository,
            VestigeProperties properties) {
        this.sarifReader = sarifReader;
        this.projectRepository = projectRepository;
        this.branchRepository = branchRepository;
        this.runRepository = runRepository;
        this.payloadRepository = payloadRepository;
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    /** What a submission resolves to - the two outcomes §4.1 distinguishes by status code. */
    public sealed interface SubmissionResult {

        AnalysisRun run();

        /** A brand-new run was created and queued. The caller answers {@code 202 Accepted}. */
        record Accepted(AnalysisRun run) implements SubmissionResult {}

        /**
         * The idempotency key matched an identical earlier submission. The caller answers {@code
         * 200}.
         */
        record Duplicate(AnalysisRun run) implements SubmissionResult {}
    }

    /**
     * @param clientIdempotencyKey the {@code Idempotency-Key} header, or {@code null}/blank if the
     *     caller did not supply one - in which case §4.1's natural key ({@code sha256(project ‖
     *     commit ‖ analyser ‖ report_digest)}) stands in for it
     * @throws dev.youneskaouani.vestige.ingestion.sarif.SarifParseException the body is not a
     *     readable SARIF document, or its first run does not name a tool (mapped to {@code 400})
     * @throws Problems.PayloadTooLarge the body exceeds {@code vestige.ingestion.max-report-bytes}
     * @throws Problems.Conflict the idempotency key was already used for a request with a different
     *     body
     */
    @Transactional
    public SubmissionResult submit(
            UUID organizationId,
            String provider,
            String owner,
            String repo,
            String branchName,
            String commitSha,
            String baseCommitSha,
            String clientIdempotencyKey,
            byte[] sarif,
            Instant now) {

        if (sarif == null || sarif.length == 0) {
            throw new Problems.BadRequest("Request body must contain a SARIF report");
        }
        long maxBytes = properties.ingestion().maxReportBytes().toBytes();
        if (sarif.length > maxBytes) {
            // ReportSizeFilter already rejects most oversized uploads from Content-Length alone;
            // this
            // is the fallback for a chunked request that filter cannot see the size of in advance.
            throw new Problems.PayloadTooLarge(
                    "Report is %d bytes, exceeding the %d byte limit"
                            .formatted(sarif.length, maxBytes));
        }
        requireText(provider, "provider");
        requireText(owner, "owner");
        requireText(repo, "repo");
        requireText(branchName, "branch");
        requireText(commitSha, "commitSha");

        String reportDigest = Sha256.hex(sarif);
        SarifReader.ToolIdentity tool = sarifReader.peekToolIdentity(sarif);

        Project project = resolveProject(organizationId, provider, owner, repo, branchName, now);
        Branch branch = resolveBranch(organizationId, project, branchName, now);

        String idempotencyKey =
                (clientIdempotencyKey != null && !clientIdempotencyKey.isBlank())
                        ? clientIdempotencyKey
                        : Sha256.hexOfFields(
                                project.getId().toString(), commitSha, tool.name(), reportDigest);

        // Two independent ways the same report can already exist: the same Idempotency-Key was
        // reused, or the natural key (project, commit, analyser, digest) matches a run that was
        // submitted under a *different* key (or none) - see AnalysisRunRepository's javadoc on the
        // second finder. Either one means "do not process this again".
        Optional<AnalysisRun> existing =
                runRepository
                        .findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey)
                        .or(
                                () ->
                                        runRepository
                                                .findByProjectIdAndCommitShaAndAnalyserNameAndReportDigest(
                                                        project.getId(),
                                                        commitSha,
                                                        tool.name(),
                                                        reportDigest));
        if (existing.isPresent()) {
            return resolveDuplicate(
                    existing.get(), project.getId(), branch.getId(), commitSha, reportDigest);
        }

        AnalysisRun run =
                new AnalysisRun(
                        UUID.randomUUID(),
                        organizationId,
                        project.getId(),
                        branch.getId(),
                        commitSha,
                        blankToNull(baseCommitSha),
                        tool.name(),
                        tool.version(),
                        reportDigest,
                        idempotencyKey,
                        now);
        // A second submission racing this exact one (same natural key, no client idempotency key
        // supplied by either) loses the
        // analysis_run_natural_key_idx/analysis_run_idempotency_key_idx
        // unique index and surfaces to its own caller as a generic 409 via
        // GlobalExceptionHandler#handleDataIntegrityViolation, rather than the
        // 200-with-original-result
        // §4.1 describes for a repeat key. That is a deliberate, narrow simplification: the race
        // window
        // is milliseconds wide, and the losing client's correct response to a 409 here is the same
        // "retry the identical request" it would already do for any other transient conflict, at
        // which
        // point it lands on the now-existing row and gets its 200. A seamless 200-on-race would
        // need a
        // catch-and-re-read loop around this method, which is more machinery than a
        // millisecond-wide
        // race justifies for v1.
        runRepository.save(run);
        payloadRepository.save(new AnalysisReportPayload(run.getId(), organizationId, sarif, now));
        jobRepository.save(new AnalysisJob(UUID.randomUUID(), organizationId, run.getId(), now));

        return new SubmissionResult.Accepted(run);
    }

    /**
     * Resolves the project this run belongs to, creating it on first submission (§8 has no separate
     * project-creation endpoint, so first contact through CI is the simplest thing that fits: a
     * project starts existing the moment something is analysed for it). A brand-new project's
     * {@code defaultBranch} is set to whatever branch it was first submitted on - usually the
     * repository's real default branch in practice, and the only signal available absent a project
     * creation flow that could ask.
     */
    private Project resolveProject(
            UUID organizationId,
            String provider,
            String owner,
            String repo,
            String branchName,
            Instant now) {
        return projectRepository
                .findByProviderAndOwnerAndName(provider, owner, repo)
                .orElseGet(
                        () ->
                                projectRepository.save(
                                        new Project(
                                                UUID.randomUUID(),
                                                organizationId,
                                                provider,
                                                owner,
                                                repo,
                                                branchName,
                                                now)));
    }

    /**
     * Resolves the branch this run belongs to, creating it on first submission. {@code
     * baselineBranchId} is deliberately left unset here - see {@code Branch}'s own javadoc for why
     * that is a documented v1 gap rather than an oversight.
     */
    private Branch resolveBranch(
            UUID organizationId, Project project, String branchName, Instant now) {
        return branchRepository
                .findByProjectIdAndName(project.getId(), branchName)
                .orElseGet(
                        () ->
                                branchRepository.save(
                                        new Branch(
                                                UUID.randomUUID(),
                                                organizationId,
                                                project.getId(),
                                                branchName,
                                                branchName.equals(project.getDefaultBranch()),
                                                now)));
    }

    /**
     * §4.1: "A repeat key returns 200 with the original run's result... A repeat key with a
     * different request body returns 409 Conflict."
     */
    private SubmissionResult resolveDuplicate(
            AnalysisRun existing,
            UUID projectId,
            UUID branchId,
            String commitSha,
            String reportDigest) {
        boolean sameRequest =
                existing.getProjectId().equals(projectId)
                        && existing.getBranchId().equals(branchId)
                        && existing.getCommitSha().equals(commitSha)
                        && existing.getReportDigest().equals(reportDigest);
        if (!sameRequest) {
            throw new Problems.Conflict(
                    "Idempotency key was already used for a different request; use a new key for a new report");
        }
        return new SubmissionResult.Duplicate(existing);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new Problems.BadRequest("'" + field + "' is required");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
