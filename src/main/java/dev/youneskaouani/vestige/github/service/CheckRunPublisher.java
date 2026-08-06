package dev.youneskaouani.vestige.github.service;

import dev.youneskaouani.vestige.gate.domain.GateOutcome;

/**
 * Posts a run's quality-gate result back to the hosting provider (§7: "Results post back to the PR
 * as a GitHub Check Run") - conclusion, summary, and annotations on the offending lines.
 *
 * <p>Takes primitives rather than entities, the same choice {@link ScmRenameResolver} makes and for
 * the same reason: this boundary should not need to know what a {@code Project} or an {@code
 * AnalysisRun} is, only enough to name one commit on one provider.
 *
 * <p>Must be best-effort. A quality gate's result is already durably stored and answered by {@code
 * GET /api/v1/runs/{id}} the moment {@code RunProcessingService} commits; a check-run call failing
 * (rate limit, token revoked, network) must never fail the run itself or roll that back - see
 * {@code NoopCheckRunPublisher} and {@code RunProcessingService}.
 */
public interface CheckRunPublisher {

    void publish(String provider, String owner, String repo, String commitSha, GateOutcome outcome);
}
