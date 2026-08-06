package dev.youneskaouani.vestige.github.service;

import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only {@link CheckRunPublisher} v1 ships. A real implementation - authenticating with {@code
 * vestige.github.token}, calling {@code POST /repos/{owner}/{repo}/check-runs}, translating {@link
 * dev.youneskaouani.vestige.gate.domain.ConditionOutcome#offendingIssueIds()} into line
 * annotations via each issue's current {@code filePath}/{@code startLine} - is exactly the kind of
 * well-scoped follow-up the task brief allows to stay an interface, a stub, and a TODO rather than
 * be built out: the contract ({@link CheckRunPublisher}) is real and already exercised by {@code
 * RunProcessingService} on every completed run, so wiring in a real implementation later is an
 * additive change, not a redesign. See README "Roadmap".
 *
 * <p>TODO(Roadmap): implement {@code GitHubCheckRunPublisher} against the real Checks API,
 * gated on {@code vestige.github.token} the same way {@link GitHubScmRenameResolver} is
 * (see {@link dev.youneskaouani.vestige.github.config.GitHubConfig}), and wire it in ahead of this
 * class for the {@code github} provider.
 */
@Component
public class NoopCheckRunPublisher implements CheckRunPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopCheckRunPublisher.class);

    @Override
    public void publish(String provider, String owner, String repo, String commitSha, GateOutcome outcome) {
        log.info(
                "Quality gate '{}' {} for {} {}/{}@{} - not posted: check-run publishing is not"
                        + " implemented in v1, see README Roadmap",
                outcome.gateName(),
                outcome.status(),
                provider,
                owner,
                repo,
                commitSha);
    }
}
