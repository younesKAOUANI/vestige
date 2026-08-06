package dev.youneskaouani.vestige.issues.domain;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for {@code GET /api/v1/projects/{id}/issues} (§8: {@code status}, {@code
 * severity}, {@code rule}, {@code since-run}). Each factory returns {@code null} for "no filter"
 * rather than an always-true predicate, which is what lets {@link Specification#and} skip it
 * entirely instead of adding a vacuous {@code AND 1=1} to the generated SQL.
 */
public final class IssueSpecifications {

    private IssueSpecifications() {
    }

    public static Specification<Issue> projectId(UUID projectId) {
        return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
    }

    public static Specification<Issue> status(IssueStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Issue> severity(Severity severity) {
        return severity == null ? null : (root, query, cb) -> cb.equal(root.get("severity"), severity);
    }

    public static Specification<Issue> ruleId(String ruleId) {
        return (ruleId == null || ruleId.isBlank())
                ? null
                : (root, query, cb) -> cb.equal(root.get("ruleId"), ruleId);
    }

    /**
     * {@code since-run}: resolved by the caller from a run id to that run's {@code createdAt}
     * (see {@code IssueQueryService}) so this class stays free of a repository dependency of its
     * own. Issues touched (opened, re-sighted, resolved or triaged) at or after that instant.
     */
    public static Specification<Issue> updatedSince(Instant threshold) {
        return threshold == null
                ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("updatedAt"), threshold);
    }
}
