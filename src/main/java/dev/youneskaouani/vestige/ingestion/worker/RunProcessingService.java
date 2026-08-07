package dev.youneskaouani.vestige.ingestion.worker;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import dev.youneskaouani.vestige.gate.service.GateEvaluationService;
import dev.youneskaouani.vestige.github.service.CheckRunPublisher;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisReportPayloadRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.ingestion.sarif.AnalysisReport;
import dev.youneskaouani.vestige.ingestion.sarif.RawFinding;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.domain.FindingRepository;
import dev.youneskaouani.vestige.issues.service.IssueTrackingService;
import dev.youneskaouani.vestige.issues.service.TrackingResult;
import dev.youneskaouani.vestige.tenancy.domain.Project;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.2's pipeline, minus claiming and outcome bookkeeping (see {@link JobLeaseService}/{@link
 * JobOutcomeService}): {@code PARSING -> MATCHING -> gate eval -> COMPLETE}, all inside the one
 * transaction §4.1 promises - "the run row, its findings, the issue mutations and the gate result
 * commit together or not at all."
 *
 * <p>Runs entirely under the caller's {@link dev.youneskaouani.vestige.tenancy.web.TenantContext},
 * which {@link OutboxWorker} sets from the claimed job before calling this - every repository call
 * in here is therefore already scoped to the right organisation by row-level security, with no
 * explicit tenant parameter needed.
 */
@Service
public class RunProcessingService {

    private static final Logger log = LoggerFactory.getLogger(RunProcessingService.class);

    private final AnalysisRunRepository runRepository;
    private final AnalysisReportPayloadRepository payloadRepository;
    private final FindingRepository findingRepository;
    private final ProjectRepository projectRepository;
    private final SarifReader sarifReader;
    private final IssueTrackingService issueTrackingService;
    private final GateEvaluationService gateEvaluationService;
    private final CheckRunPublisher checkRunPublisher;
    private final VestigeProperties properties;

    public RunProcessingService(
            AnalysisRunRepository runRepository,
            AnalysisReportPayloadRepository payloadRepository,
            FindingRepository findingRepository,
            ProjectRepository projectRepository,
            SarifReader sarifReader,
            IssueTrackingService issueTrackingService,
            GateEvaluationService gateEvaluationService,
            CheckRunPublisher checkRunPublisher,
            VestigeProperties properties) {
        this.runRepository = runRepository;
        this.payloadRepository = payloadRepository;
        this.findingRepository = findingRepository;
        this.projectRepository = projectRepository;
        this.sarifReader = sarifReader;
        this.issueTrackingService = issueTrackingService;
        this.gateEvaluationService = gateEvaluationService;
        this.checkRunPublisher = checkRunPublisher;
        this.properties = properties;
    }

    @Transactional
    public void process(UUID runId, Instant now) {
        AnalysisRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Run "
                                                        + runId
                                                        + " was claimed but is now missing"));
        byte[] sarif =
                payloadRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Run " + runId + " has no stored report payload"))
                        .getSarif();

        run.markParsing(now);

        List<Finding> persisted = new ArrayList<>();
        AnalysisReport report =
                sarifReader.read(
                        sarif,
                        properties.ingestion().findingBatchSize(),
                        batch -> {
                            List<Finding> entities =
                                    batch.stream().map(raw -> toEntity(run, raw, now)).toList();
                            findingRepository.saveAll(entities);
                            persisted.addAll(entities);
                        });
        run.describeAnalyser(report.analyserName(), report.analyserVersion(), now);

        run.markMatching(now);
        Project project =
                projectRepository
                        .findById(run.getProjectId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Run "
                                                        + runId
                                                        + " has no project "
                                                        + run.getProjectId()));

        TrackingResult tracking =
                issueTrackingService.track(
                        run.getOrganizationId(),
                        run.getProjectId(),
                        run.getBranchId(),
                        run.getId(),
                        run.getCommitSha(),
                        run.getBaseCommitSha(),
                        project.getProvider(),
                        project.getOwner(),
                        project.getName(),
                        persisted,
                        now);

        GateOutcome gateOutcome =
                gateEvaluationService.evaluate(
                        run.getOrganizationId(),
                        run.getProjectId(),
                        run.getId(),
                        tracking.gateIssues(),
                        now);

        run.markCompleted(persisted.size(), now);

        publishCheckRun(project, run, gateOutcome);
    }

    private Finding toEntity(AnalysisRun run, RawFinding raw, Instant now) {
        return new Finding(
                UUID.randomUUID(),
                run.getOrganizationId(),
                run.getId(),
                raw.ruleId(),
                raw.severity(),
                raw.message(),
                raw.filePath(),
                raw.symbolPath(),
                raw.startLine(),
                raw.endLine(),
                raw.startColumn(),
                raw.endColumn(),
                raw.lineSnippet(),
                raw.fingerprints().identityFp(),
                raw.fingerprints().contextFp(),
                raw.fingerprints().weakFp(),
                now);
    }

    /**
     * Best-effort by construction: a check-run failure is logged and swallowed here, not
     * propagated, so it can never roll back a run that is otherwise fully processed and already
     * durably COMPLETE - see {@link CheckRunPublisher}'s own javadoc.
     */
    private void publishCheckRun(Project project, AnalysisRun run, GateOutcome outcome) {
        try {
            checkRunPublisher.publish(
                    project.getProvider(),
                    project.getOwner(),
                    project.getName(),
                    run.getCommitSha(),
                    outcome);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to publish check run for run {} - the run itself is still COMPLETE",
                    run.getId(),
                    e);
        }
    }
}
