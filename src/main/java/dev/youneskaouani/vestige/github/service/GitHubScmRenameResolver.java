package dev.youneskaouani.vestige.github.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves renames against GitHub's compare API: {@code GET
 * /repos/{owner}/{repo}/compare/{base}...{head}}, whose {@code files[]} array reports a {@code
 * previous_filename} for every entry with {@code status: "renamed"} (§3.2).
 *
 * <p>Built on {@link RestClient} rather than {@code RestTemplate} - the modern synchronous client
 * Spring Framework 6.1 introduced specifically to replace it - which is also what makes this class
 * testable with {@code MockRestServiceServer.bindTo(RestClient.Builder)} without a real GitHub
 * call (see {@code GitHubScmRenameResolverTest}).
 */
public final class GitHubScmRenameResolver implements ScmRenameResolver {

    private static final Logger log = LoggerFactory.getLogger(GitHubScmRenameResolver.class);

    private final RestClient restClient;

    public GitHubScmRenameResolver(RestClient.Builder builder, String token) {
        RestClient.Builder configured = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            configured = configured.defaultHeader("Authorization", "Bearer " + token);
        }
        this.restClient = configured.build();
    }

    @Override
    public Map<String, String> renamesBetween(String owner, String repo, String baseCommitSha, String headCommitSha) {
        if (baseCommitSha == null || baseCommitSha.isBlank()) {
            // Most commonly: the first run ever recorded on this branch. Nothing to compare against
            // is not an error - see the interface javadoc.
            return Map.of();
        }
        try {
            JsonNode response = restClient
                    .get()
                    .uri("/repos/{owner}/{repo}/compare/{base}...{head}", owner, repo, baseCommitSha, headCommitSha)
                    .retrieve()
                    .body(JsonNode.class);
            return extractRenames(response);
        } catch (RestClientException e) {
            log.warn(
                    "GitHub compare API unreachable for {}/{} {}...{}, degrading to no renames (§3.2): {}",
                    owner,
                    repo,
                    baseCommitSha,
                    headCommitSha,
                    e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> extractRenames(JsonNode response) {
        Map<String, String> renames = new LinkedHashMap<>();
        if (response == null) {
            return renames;
        }
        for (JsonNode file : response.path("files")) {
            if (!"renamed".equals(file.path("status").asText(null))) {
                continue;
            }
            String previous = file.path("previous_filename").asText(null);
            String current = file.path("filename").asText(null);
            if (previous != null && !previous.isBlank() && current != null && !current.isBlank()) {
                renames.put(previous, current);
            }
        }
        return renames;
    }
}
