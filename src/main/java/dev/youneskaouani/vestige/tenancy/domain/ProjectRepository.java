package dev.youneskaouani.vestige.tenancy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Row-level security already restricts this to the current tenant, so the query does not repeat
     * the organisation in its WHERE clause. That is the point of enforcing isolation in the
     * database: a query that forgets the tenant returns nothing rather than everything.
     */
    Optional<Project> findByProviderAndOwnerAndName(String provider, String owner, String name);

    List<Project> findAllByOrganizationId(UUID organizationId);
}
