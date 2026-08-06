package dev.youneskaouani.vestige.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One submitted SARIF report and everything known about processing it. */
@Entity
@Table(name = "analysis_run")
public class AnalysisRun {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "base_commit_sha")
    private String baseCommitSha;

    @Column(name = "analyser_name", nullable = false)
    private String analyserName;

    @Column(name = "analyser_version", nullable = false)
    private String analyserVersion;

    @Column(name = "report_digest", nullable = false)
    private String reportDigest;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "finding_count", nullable = false)
    private int findingCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AnalysisRun() {
        // for JPA
    }

    public AnalysisRun(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID branchId,
            String commitSha,
            String baseCommitSha,
            String analyserName,
            String analyserVersion,
            String reportDigest,
            String idempotencyKey,
            Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.branchId = branchId;
        this.commitSha = commitSha;
        this.baseCommitSha = baseCommitSha;
        this.analyserName = analyserName;
        this.analyserVersion = analyserVersion;
        this.reportDigest = reportDigest;
        this.idempotencyKey = idempotencyKey;
        this.status = RunStatus.RECEIVED;
        this.attemptCount = 0;
        this.findingCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The worker has claimed this run and started streaming the SARIF payload (§4.2, §4.3). */
    public void markParsing(Instant now) {
        this.status = RunStatus.PARSING;
        this.attemptCount++;
        this.updatedAt = now;
    }

    /** Parsing finished; the §3.3 ladder is now running against the branch's previous issues. */
    public void markMatching(Instant now) {
        this.status = RunStatus.MATCHING;
        this.updatedAt = now;
    }

    public void markCompleted(int findingCount, Instant now) {
        this.status = RunStatus.COMPLETED;
        this.findingCount = findingCount;
        this.failureReason = null;
        this.updatedAt = now;
        this.completedAt = now;
    }

    /** This attempt failed. Not terminal by itself - the outbox worker decides retry vs {@link #markQuarantined}. */
    public void markFailed(String reason, Instant now) {
        this.status = RunStatus.FAILED;
        this.failureReason = truncate(reason);
        this.updatedAt = now;
    }

    /** Terminal: {@code vestige.worker.max-attempts} exhausted. A {@code PoisonReport} is written alongside. */
    public void markQuarantined(String reason, Instant now) {
        this.status = RunStatus.QUARANTINED;
        this.failureReason = truncate(reason);
        this.updatedAt = now;
    }

    /** Analyser identity is discovered while parsing, not supplied by the client. */
    public void describeAnalyser(String name, String version, Instant now) {
        this.analyserName = name;
        this.analyserVersion = version;
        this.updatedAt = now;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 2000 ? reason : reason.substring(0, 2000);
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

    public String getCommitSha() {
        return commitSha;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public String getAnalyserName() {
        return analyserName;
    }

    public String getAnalyserVersion() {
        return analyserVersion;
    }

    public String getReportDigest() {
        return reportDigest;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getFindingCount() {
        return findingCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
