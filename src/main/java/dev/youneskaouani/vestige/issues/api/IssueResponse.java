package dev.youneskaouani.vestige.issues.api;

import dev.youneskaouani.vestige.issues.domain.Issue;
import java.time.Instant;
import java.util.UUID;

/** An {@link Issue} as the API shows it: its current sighting plus its cross-run identity. */
public record IssueResponse(
        UUID id,
        UUID projectId,
        UUID branchId,
        String ruleId,
        String severity,
        String message,
        String filePath,
        String symbolPath,
        int startLine,
        String status,
        UUID firstSeenRunId,
        UUID lastSeenRunId,
        String introducedAtCommit,
        Instant createdAt,
        Instant updatedAt) {

    public static IssueResponse of(Issue issue) {
        return new IssueResponse(
                issue.getId(),
                issue.getProjectId(),
                issue.getBranchId(),
                issue.getRuleId(),
                issue.getSeverity().name(),
                issue.getMessage(),
                issue.getFilePath(),
                issue.getSymbolPath(),
                issue.getStartLine(),
                issue.getStatus().name(),
                issue.getFirstSeenRunId(),
                issue.getLastSeenRunId(),
                issue.getIntroducedAtCommit(),
                issue.getCreatedAt(),
                issue.getUpdatedAt());
    }
}
