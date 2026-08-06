package dev.youneskaouani.vestige.issues.api;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/v1/issues/{id}}'s body (§8). {@code justification} is validated against {@code
 * status} in {@code TriageService}, not with a bean-validation annotation here, since whether it is
 * required depends on the value of another field ({@link IssueStatus#requiresTriage()}).
 *
 * @param actor caller-supplied, unverified - see {@code TriageEvent}'s class javadoc
 */
public record TriagePatchRequest(@NotNull IssueStatus status, @NotBlank String actor, String justification) {
}
