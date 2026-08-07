package dev.youneskaouani.vestige.ingestion.api;

import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluation;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluationRepository;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.ingestion.service.RunIngestionService;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/runs} and {@code GET /api/v1/runs/{id}} (§8).
 *
 * <p>The report body is taken as raw bytes, not JSON or multipart: SARIF is itself already a JSON
 * document, so wrapping it in another JSON envelope (base64, most likely) would only inflate a
 * report that can be 200 MB for no benefit, and a multipart boundary buys nothing over a plain
 * {@code POST} body for a single-file upload. A CI job posts the report with a single {@code curl
 * --data-binary}.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunIngestionService ingestionService;
    private final AnalysisRunRepository runRepository;
    private final QualityGateEvaluationRepository evaluationRepository;

    public RunController(
            RunIngestionService ingestionService,
            AnalysisRunRepository runRepository,
            QualityGateEvaluationRepository evaluationRepository) {
        this.ingestionService = ingestionService;
        this.runRepository = runRepository;
        this.evaluationRepository = evaluationRepository;
    }

    @PostMapping
    public ResponseEntity<RunResponse> submit(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam String branch,
            @RequestParam String commitSha,
            @RequestParam(required = false) String baseCommitSha,
            @RequestParam(defaultValue = "github") String provider,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] sarif) {

        UUID organizationId = TenantContext.require();
        RunIngestionService.SubmissionResult result =
                ingestionService.submit(
                        organizationId,
                        provider,
                        owner,
                        repo,
                        branch,
                        commitSha,
                        baseCommitSha,
                        idempotencyKey,
                        sarif,
                        Instant.now());

        HttpStatus status =
                (result instanceof RunIngestionService.SubmissionResult.Duplicate)
                        ? HttpStatus.OK
                        : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(toResponse(result.run()));
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        AnalysisRun run =
                runRepository.findById(id).orElseThrow(() -> new Problems.NotFound("Run", id));
        return toResponse(run);
    }

    private RunResponse toResponse(AnalysisRun run) {
        String gateResultJson =
                evaluationRepository
                        .findByAnalysisRunId(run.getId())
                        .map(QualityGateEvaluation::getResultJson)
                        .orElse(null);
        return RunResponse.of(run, gateResultJson);
    }
}
