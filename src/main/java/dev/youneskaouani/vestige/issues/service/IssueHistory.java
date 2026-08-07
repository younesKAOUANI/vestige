package dev.youneskaouani.vestige.issues.service;

import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import java.util.List;

/**
 * {@code GET /api/v1/issues/{id}/history}'s answer (§8): "full finding + triage timeline" - every
 * sighting of the issue, and every human decision made about it, both oldest first.
 */
public record IssueHistory(Issue issue, List<Finding> findings, List<TriageEvent> triageEvents) {}
