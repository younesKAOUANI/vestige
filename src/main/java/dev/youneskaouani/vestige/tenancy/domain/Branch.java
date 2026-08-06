package dev.youneskaouani.vestige.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A branch of a {@link Project}. Analysis is always branch-scoped (§2.2): matching compares a run
 * against the previous run <em>on the same branch</em>, because "does my PR make things worse than
 * main?" is the question teams actually ask.
 *
 * <p>{@code baselineBranchId} is how a feature branch inherits main's history instead of starting
 * from an empty issue set the first time it is analysed - a real gap in v1 is that nothing yet
 * populates it automatically from the SCM's "base branch" (see README "Roadmap"); today it is set
 * explicitly or left null, in which case the branch simply starts empty, exactly like main did.
 */
@Entity
@Table(name = "branch")
public class Branch {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_reference", nullable = false)
    private boolean reference;

    @Column(name = "baseline_branch_id")
    private UUID baselineBranchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Branch() {
        // for JPA
    }

    public Branch(UUID id, UUID organizationId, UUID projectId, String name, boolean reference, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.name = name;
        this.reference = reference;
        this.createdAt = createdAt;
    }

    public void baselineOn(UUID referenceBranchId) {
        this.baselineBranchId = referenceBranchId;
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

    public String getName() {
        return name;
    }

    public boolean isReference() {
        return reference;
    }

    public UUID getBaselineBranchId() {
        return baselineBranchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
