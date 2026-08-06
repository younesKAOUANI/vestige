package dev.youneskaouani.vestige.issues.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    /**
     * Every previously-tracked issue on a branch, silenced or not, resolved or not - the "P" in
     * §3.3's {@code match(P, C)}. Deliberately not filtered to {@code OPEN}: a
     * {@code RESOLVED_FIXED} issue must still be matchable so it can reopen (§2.2), and a silenced
     * issue must keep accumulating findings without its status being disturbed - see
     * {@code IssueLifecycle} and {@code IssueTrackingService}.
     */
    List<Issue> findAllByBranchId(UUID branchId);
}
