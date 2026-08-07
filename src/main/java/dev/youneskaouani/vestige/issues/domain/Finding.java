package dev.youneskaouani.vestige.issues.domain;

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
 * One raw result from one analyser in one run (§2.1). Immutable in the sense the architecture doc
 * means it: once written, {@code ruleId}/{@code severity}/.../{@code fingerprints} never change -
 * the only field this class ever mutates after construction is {@link #issueId}/{@link #matchRung},
 * set exactly once by the matcher (§3.3) before the run's transaction commits.
 *
 * <p>{@code issueId} is nullable at the type level because a finding exists, in memory, before it
 * has been matched (§4.3: parse, then match, in the same transaction) - but no finding is ever
 * durably visible to another transaction without one, since the whole run commits atomically or not
 * at all.
 */
@Entity
@Table(name = "finding")
public class Finding {

    @Id private UUID id;

    /**
     * {@code seq bigint generated always as identity} - the matcher's tie-break ordinal (§3.3,
     * "lowest finding id"). Read-only from JPA's side: the identity column is populated by Postgres
     * on INSERT, and nothing in this codebase needs to read a finding's own {@code seq} back within
     * the same transaction that inserted it - a {@code seq} only matters once a finding is used as
     * a {@code PreviousIssueCandidate} in some later run, by which point it has long since been
     * fetched fresh by a query. Mapping it {@code insertable = false, updatable = false} (rather
     * than adding an after-insert refresh) is therefore correct, not merely convenient.
     */
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "issue_id")
    private UUID issueId;

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

    @Column(name = "end_line", nullable = false)
    private int endLine;

    @Column(name = "start_column", nullable = false)
    private int startColumn;

    @Column(name = "end_column", nullable = false)
    private int endColumn;

    @Column(name = "line_snippet")
    private String lineSnippet;

    @Column(name = "identity_fp")
    private String identityFp;

    @Column(name = "context_fp")
    private String contextFp;

    @Column(name = "weak_fp", nullable = false)
    private String weakFp;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_rung")
    private MatchRung matchRung;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Finding() {
        // for JPA
    }

    public Finding(
            UUID id,
            UUID organizationId,
            UUID analysisRunId,
            String ruleId,
            Severity severity,
            String message,
            String filePath,
            String symbolPath,
            int startLine,
            int endLine,
            int startColumn,
            int endColumn,
            String lineSnippet,
            String identityFp,
            String contextFp,
            String weakFp,
            Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.analysisRunId = analysisRunId;
        this.ruleId = ruleId;
        this.severity = severity;
        this.message = message;
        this.filePath = filePath;
        this.symbolPath = symbolPath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startColumn = startColumn;
        this.endColumn = endColumn;
        this.lineSnippet = lineSnippet;
        this.identityFp = identityFp;
        this.contextFp = contextFp;
        this.weakFp = weakFp;
        this.createdAt = createdAt;
    }

    /** Set exactly once, by the matcher, before the run's transaction commits (§4.3). */
    public void assignToIssue(UUID issueId, MatchRung matchRung) {
        if (this.issueId != null) {
            throw new IllegalStateException(
                    "Finding " + id + " is already assigned to issue " + this.issueId);
        }
        this.issueId = issueId;
        this.matchRung = matchRung;
    }

    public UUID getId() {
        return id;
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public UUID getIssueId() {
        return issueId;
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

    public int getEndLine() {
        return endLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public String getLineSnippet() {
        return lineSnippet;
    }

    public String getIdentityFp() {
        return identityFp;
    }

    public String getContextFp() {
        return contextFp;
    }

    public String getWeakFp() {
        return weakFp;
    }

    public MatchRung getMatchRung() {
        return matchRung;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
