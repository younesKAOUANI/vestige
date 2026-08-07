package dev.youneskaouani.vestige.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * No real network call: {@link MockRestServiceServer#bindTo(RestClient.Builder)} intercepts the
 * {@link RestClient} this resolver builds, so these tests pin down the URL shape and the JSON
 * extraction without depending on GitHub being reachable (which the sandbox this was built in
 * cannot assume anyway - see README).
 */
class GitHubScmRenameResolverTest {

    @Test
    void extractsOnlyRenamedFiles() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "https://api.github.com/repos/acme/widgets/compare/base123...head456"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                """
                        {
                          "files": [
                            { "filename": "src/Billing.java", "status": "renamed",
                              "previous_filename": "src/legacy/Billing.java" },
                            { "filename": "src/Other.java", "status": "modified" }
                          ]
                        }
                        """,
                                MediaType.APPLICATION_JSON));

        GitHubScmRenameResolver resolver = new GitHubScmRenameResolver(builder, "token123");

        Map<String, String> renames =
                resolver.renamesBetween("acme", "widgets", "base123", "head456");

        assertThat(renames)
                .containsExactly(Map.entry("src/legacy/Billing.java", "src/Billing.java"));
        server.verify();
    }

    @Test
    void returnsEmptyWithoutMakingARequestWhenThereIsNoBaseCommit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build(); // expects nothing

        GitHubScmRenameResolver resolver = new GitHubScmRenameResolver(builder, "token123");

        assertThat(resolver.renamesBetween("acme", "widgets", null, "head456")).isEmpty();
        server.verify();
    }

    @Test
    void degradesToEmptyWhenGitHubIsUnreachable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "https://api.github.com/repos/acme/widgets/compare/base123...head456"))
                .andRespond(withServerError());

        GitHubScmRenameResolver resolver = new GitHubScmRenameResolver(builder, "token123");

        assertThat(resolver.renamesBetween("acme", "widgets", "base123", "head456")).isEmpty();
        server.verify();
    }

    @Test
    void ignoresAResponseWithNoRenamedFiles() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "https://api.github.com/repos/acme/widgets/compare/base123...head456"))
                .andRespond(withSuccess("{ \"files\": [] }", MediaType.APPLICATION_JSON));

        GitHubScmRenameResolver resolver = new GitHubScmRenameResolver(builder, "token123");

        assertThat(resolver.renamesBetween("acme", "widgets", "base123", "head456")).isEmpty();
    }
}
