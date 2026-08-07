package dev.youneskaouani.vestige.issues.domain;

import dev.youneskaouani.vestige.common.domain.IssueLifecycle;
import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The tracked claim a {@link Finding} is matched into (§2.1, §2.2): a stable identity that spans
 * many runs, with mutable status. {@code ruleId}/{@code severity}/.../{@code startLine} always
 * reflect the <em>most recent</em> sighting, not the first - matching always compares the head
 * commit against the previous one, so "what does this look like right now" is the useful answer to
 * keep on the row a UI lists. The full history of sightings lives in {@code finding.issue_id}, not
 * here.
 */
@Entity
@Table(name = "issue")
public class Issue {

    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String message;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "symbol_path")
    private String symbolPath;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueStatus status;

    @Column(name = "first_seen_run_id", nullable = false)
    private UUID firstSeenRunId;

    @Column(name = "last_seen_run_id", nullable = false)
    private UUID lastSeenRunId;

    @Column(name = "introduced_at_commit", nullable = false)
    private String introducedAtCommit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Issue() {
        // for JPA
    }

    /**
     * Opens a brand-new issue from a finding that matched nothing (§3.3: {@code for c in
     * unmatched_C}).
     */
    public Issue(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID branchId,
            String ruleId,
            Severity severity,
            String message,
            String filePath,
            String symbolPath,
            int startLine,
            UUID firstSeenRunId,
            String introducedAtCommit,
            Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.branchId = branchId;
        this.ruleId = ruleId;
        this.severity = severity;
        this.message = message;
        this.filePath = filePath;
        this.symbolPath = symbolPath;
        this.startLine = startLine;
        this.status = IssueStatus.OPEN;
        this.firstSeenRunId = firstSeenRunId;
        this.lastSeenRunId = firstSeenRunId;
        this.introducedAtCommit = introducedAtCommit;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Re-attaches this issue to a finding that matched it again in {@code runId} (§3.3): status
     * moves per {@link IssueLifecycle#afterSighting}, and the "current sighting" fields refresh -
     * see the class javadoc for why that is the field, not the first sighting.
     */
    public void recordSighting(
            String ruleId,
            Severity severity,
            String message,
            String filePath,
            String symbolPath,
            int startLine,
            UUID runId,
            Instant now) {
        this.status = IssueLifecycle.afterSighting(this.status);
        this.ruleId = ruleId;
        this.severity = severity;
        this.message = message;
        this.filePath = filePath;
        this.symbolPath = symbolPath;
        this.startLine = startLine;
        this.lastSeenRunId = runId;
        this.updatedAt = now;
    }

    /** This run's matcher did not see this issue again (§3.3: {@code for p in unmatched_P}). */
    public void recordDisappearance(Instant now) {
        IssueStatus before = this.status;
        this.status = IssueLifecycle.afterDisappearance(this.status);
        if (this.status != before) {
            this.updatedAt = now;
        }
    }

    /**
     * A human triage decision (§6). Deliberately not restricted to {@link
     * IssueStatus#requiresTriage()}'s two statuses: {@code PATCH /api/v1/issues/{id}} is also how a
     * mistaken triage gets corrected back to {@code OPEN} - the "justification required" rule is
     * enforced once, in {@code TriageService}, alongside writing the {@code TriageEvent} this
     * change must never happen without.
     */
    public void applyTriage(IssueStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getSymbolPath() {
        return symbolPath;
    }

    public int getStartLine() {
        return startLine;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public UUID getFirstSeenRunId() {
        return firstSeenRunId;
    }

    public UUID getLastSeenRunId() {
        return lastSeenRunId;
    }

    public String getIntroducedAtCommit() {
        return introducedAtCommit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
