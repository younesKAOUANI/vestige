package dev.youneskaouani.vestige.tenancy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByProjectIdAndName(UUID projectId, String name);

    List<Branch> findAllByProjectId(UUID projectId);
}
