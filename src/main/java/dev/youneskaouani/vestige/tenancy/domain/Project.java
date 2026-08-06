package dev.youneskaouani.vestige.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A repository under an organization. */
@Entity
@Table(name = "project")
public class Project {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** The hosting provider, e.g. {@code github}. */
    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Project() {
        // for JPA
    }

    public Project(
            UUID id,
            UUID organizationId,
            String provider,
            String owner,
            String name,
            String defaultBranch,
            Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.provider = provider;
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.createdAt = createdAt;
    }

    /** {@code owner/name}, the form used in webhooks and in the UI. */
    public String fullName() {
        return owner + "/" + name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
