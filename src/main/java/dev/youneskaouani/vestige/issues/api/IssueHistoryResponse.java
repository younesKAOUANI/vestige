package dev.youneskaouani.vestige.issues.api;

import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.service.IssueHistory;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/v1/issues/{id}/history} (§8): every sighting, and every triage decision, oldest first. */
public record IssueHistoryResponse(IssueResponse issue, List<Sighting> findings, List<TriageEntry> triageEvents) {

    public static IssueHistoryResponse of(IssueHistory history) {
        return new IssueHistoryResponse(
                IssueResponse.of(history.issue()),
                history.findings().stream().map(Sighting::of).toList(),
                history.triageEvents().stream().map(TriageEntry::of).toList());
    }

    public record Sighting(
            UUID id,
            UUID analysisRunId,
            String ruleId,
            String severity,
            String message,
            String filePath,
            int startLine,
            String matchRung,
            Instant createdAt) {

        static Sighting of(Finding finding) {
            return new Sighting(
                    finding.getId(),
                    finding.getAnalysisRunId(),
                    finding.getRuleId(),
                    finding.getSeverity().name(),
                    finding.getMessage(),
                    finding.getFilePath(),
                    finding.getStartLine(),
                    finding.getMatchRung() == null ? null : finding.getMatchRung().name(),
                    finding.getCreatedAt());
        }
    }

    public record TriageEntry(
            long sequenceNumber,
            String actor,
            String fromStatus,
            String toStatus,
            String justification,
            Instant occurredAt,
            String entryHash) {

        static TriageEntry of(TriageEvent event) {
            return new TriageEntry(
                    event.getSequenceNumber(),
                    event.getActor(),
                    event.getFromStatus().name(),
                    event.getToStatus().name(),
                    event.getJustification(),
                    event.getOccurredAt(),
                    event.getEntryHash());
        }
    }
}
