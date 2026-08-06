package dev.youneskaouani.vestige.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.common.hash.Sha256;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisJobRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisReportPayloadRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.ingestion.sarif.SarifReader;
import dev.youneskaouani.vestige.tenancy.domain.Branch;
import dev.youneskaouani.vestige.tenancy.domain.BranchRepository;
import dev.youneskaouani.vestige.tenancy.domain.Project;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

/**
 * Exercises §4.1's idempotency contract with every collaborator mocked - no database, no Spring
 * context - since the interesting behaviour here (which of 202/200/409 a given request earns) is a
 * pure function of what the repositories report, not of how they are implemented.
 */
@ExtendWith(MockitoExtension.class)
class RunIngestionServiceTest {

    private static final byte[] SARIF = "{\"runs\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private SarifReader sarifReader;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private AnalysisRunRepository runRepository;

    @Mock
    private AnalysisReportPayloadRepository payloadRepository;

    @Mock
    private AnalysisJobRepository jobRepository;

    private RunIngestionService service;

    @BeforeEach
    void setUp() {
        VestigeProperties properties = new VestigeProperties(
                new VestigeProperties.Ingestion(DataSize.ofMegabytes(200), 1000), null, null, null);
        service = new RunIngestionService(
                sarifReader, projectRepository, branchRepository, runRepository, payloadRepository, jobRepository, properties);
    }

    @Test
    @DisplayName("a brand-new submission creates the project and branch, queues a job, and is Accepted")
    void acceptsANewSubmissionAndQueuesAJob() {
        when(sarifReader.peekToolIdentity(SARIF)).thenReturn(new SarifReader.ToolIdentity("ESLint", "8.0.0"));
        when(projectRepository.findByProviderAndOwnerAndName("github", "acme", "widgets"))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.findByProjectIdAndName(any(), eq("main"))).thenReturn(Optional.empty());
        when(branchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Neither the idempotency-key nor the natural-key lookup is stubbed: Mockito's default answer
        // for an Optional-returning method is Optional.empty(), which is exactly "no prior run" here.

        RunIngestionService.SubmissionResult result = service.submit(
                ORG_ID, "github", "acme", "widgets", "main", "commit123", null, null, SARIF, NOW);

        assertThat(result).isInstanceOf(RunIngestionService.SubmissionResult.Accepted.class);
        AnalysisRun run = result.run();
        assertThat(run.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(run.getCommitSha()).isEqualTo("commit123");
        assertThat(run.getAnalyserName()).isEqualTo("ESLint");
        assertThat(run.getAnalyserVersion()).isEqualTo("8.0.0");
        assertThat(run.getIdempotencyKey()).isNotBlank();

        verify(runRepository).save(run);
        verify(payloadRepository).save(any());
        verify(jobRepository).save(any());
    }

    @Test
    @DisplayName("a new project's default branch becomes whichever branch it was first analysed on")
    void firstSubmissionSeedsTheProjectsDefaultBranch() {
        when(sarifReader.peekToolIdentity(SARIF)).thenReturn(new SarifReader.ToolIdentity("ESLint", "8.0.0"));
        when(projectRepository.findByProviderAndOwnerAndName(any(), any(), any())).thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.findByProjectIdAndName(any(), any())).thenReturn(Optional.empty());
        when(branchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submit(ORG_ID, "github", "acme", "widgets", "develop", "commit123", null, null, SARIF, NOW);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getDefaultBranch()).isEqualTo("develop");

        ArgumentCaptor<Branch> branchCaptor = ArgumentCaptor.forClass(Branch.class);
        verify(branchRepository).save(branchCaptor.capture());
        assertThat(branchCaptor.getValue().isReference()).isTrue();
    }

    @Test
    @DisplayName("a repeated Idempotency-Key with the same body returns the original run, unprocessed again")
    void returnsTheOriginalRunOnAnIdenticalRepeat() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        AnalysisRun existing = new AnalysisRun(
                UUID.randomUUID(), ORG_ID, projectId, branchId, "commit123", null, "ESLint", "8.0.0",
                reportDigestOf(SARIF), "client-key", NOW);

        when(sarifReader.peekToolIdentity(SARIF)).thenReturn(new SarifReader.ToolIdentity("ESLint", "8.0.0"));
        when(projectRepository.findByProviderAndOwnerAndName(any(), any(), any()))
                .thenReturn(Optional.of(projectWith(projectId)));
        when(branchRepository.findByProjectIdAndName(any(), any())).thenReturn(Optional.of(branchWith(branchId)));
        when(runRepository.findByOrganizationIdAndIdempotencyKey(ORG_ID, "client-key")).thenReturn(Optional.of(existing));

        RunIngestionService.SubmissionResult result = service.submit(
                ORG_ID, "github", "acme", "widgets", "main", "commit123", "client-key", null, SARIF, NOW);

        assertThat(result).isInstanceOf(RunIngestionService.SubmissionResult.Duplicate.class);
        assertThat(result.run()).isSameAs(existing);
        verify(runRepository, never()).save(any());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("a repeated Idempotency-Key with a different body is a 409, not a silent overwrite")
    void rejectsAnIdempotencyKeyReusedForADifferentReport() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        AnalysisRun existing = new AnalysisRun(
                UUID.randomUUID(), ORG_ID, projectId, branchId, "commit123", null, "ESLint", "8.0.0",
                "a-completely-different-digest", "client-key", NOW);

        when(sarifReader.peekToolIdentity(SARIF)).thenReturn(new SarifReader.ToolIdentity("ESLint", "8.0.0"));
        when(projectRepository.findByProviderAndOwnerAndName(any(), any(), any()))
                .thenReturn(Optional.of(projectWith(projectId)));
        when(branchRepository.findByProjectIdAndName(any(), any())).thenReturn(Optional.of(branchWith(branchId)));
        when(runRepository.findByOrganizationIdAndIdempotencyKey(ORG_ID, "client-key")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(
                        ORG_ID, "github", "acme", "widgets", "main", "commit123", "client-key", null, SARIF, NOW))
                .isInstanceOf(Problems.Conflict.class);
    }

    @Test
    @DisplayName("a report over the configured ceiling is 413, before anything is parsed")
    void rejectsAnOversizedReport() {
        VestigeProperties tinyLimit =
                new VestigeProperties(new VestigeProperties.Ingestion(DataSize.ofBytes(4), 1000), null, null, null);
        RunIngestionService tightService = new RunIngestionService(
                sarifReader, projectRepository, branchRepository, runRepository, payloadRepository, jobRepository, tinyLimit);

        assertThatThrownBy(() -> tightService.submit(
                        ORG_ID, "github", "acme", "widgets", "main", "commit123", null, null, SARIF, NOW))
                .isInstanceOf(Problems.PayloadTooLarge.class);
    }

    @Test
    @DisplayName("an empty body is rejected before the SARIF reader is even asked to look at it")
    void rejectsAnEmptyBody() {
        assertThatThrownBy(() -> service.submit(
                        ORG_ID, "github", "acme", "widgets", "main", "commit123", null, null, new byte[0], NOW))
                .isInstanceOf(Problems.BadRequest.class);
    }

    @Test
    @DisplayName("a blank required field is rejected before any repository is touched")
    void rejectsAMissingCommitSha() {
        assertThatThrownBy(() -> service.submit(ORG_ID, "github", "acme", "widgets", "main", " ", null, null, SARIF, NOW))
                .isInstanceOf(Problems.BadRequest.class);
    }

    private static Project projectWith(UUID id) {
        return new Project(id, ORG_ID, "github", "acme", "widgets", "main", NOW);
    }

    private static Branch branchWith(UUID id) {
        return new Branch(id, ORG_ID, UUID.randomUUID(), "main", true, NOW);
    }

    private static String reportDigestOf(byte[] bytes) {
        return Sha256.hex(bytes);
    }
}
