package dev.youneskaouani.vestige.issues.service;

import dev.youneskaouani.vestige.gate.domain.GateInput;
import java.util.List;

/**
 * The outcome of {@link IssueTrackingService#track}, ready to feed the quality gate and to answer a
 * run's own status response.
 *
 * @param gateIssues every issue the matcher touched this run - matched again, reopened, or newly
 *     opened - in exactly the shape {@link GateInput} documents its own contract as needing (an
 *     issue that predates this run and was not re-sighted cannot exist, since the analyser
 *     re-reports the whole codebase every time)
 */
public record TrackingResult(
        List<GateInput.GateIssue> gateIssues,
        int newIssueCount,
        int matchedCount,
        int reopenedCount,
        int autoResolvedCount) {}
