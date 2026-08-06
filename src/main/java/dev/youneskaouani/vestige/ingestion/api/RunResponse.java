package dev.youneskaouani.vestige.ingestion.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/v1/runs} and {@code GET /api/v1/runs/{id}}'s shared response shape: run status
 * plus gate result (§8), the gate embedded as a raw JSON sub-document via {@link JsonRawValue}
 * rather than re-parsed and re-typed - {@link
 * dev.youneskaouani.vestige.gate.domain.QualityGateEvaluation#getResultJson()} is already exactly
 * the JSON this response wants to show, computed once by {@code GateEvaluationService}.
 *
 * @param gateResult {@code null} until the run reaches {@code COMPLETED} and a gate has actually
 *     been evaluated - a freshly-accepted run has no gate result yet
 */
public record RunResponse(
        UUID id,
        UUID projectId,
        UUID branchId,
        String commitSha,
        String baseCommitSha,
        String analyserName,
        String analyserVersion,
        String status,
        String failureReason,
        int findingCount,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        @JsonRawValue String gateResult) {

    public static RunResponse of(AnalysisRun run, String gateResultJson) {
        return new RunResponse(
                run.getId(),
                run.getProjectId(),
                run.getBranchId(),
                run.getCommitSha(),
                run.getBaseCommitSha(),
                run.getAnalyserName(),
                run.getAnalyserVersion(),
                run.getStatus().name(),
                run.getFailureReason(),
                run.getFindingCount(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getCompletedAt(),
                gateResultJson);
    }
}
