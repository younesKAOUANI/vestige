package dev.youneskaouani.vestige.github.service;

import java.util.Map;

/**
 * The resolver used when no GitHub token is configured ({@code vestige.github.token} blank - see
 * {@code GitHubConfig}). Matching still works without it - identity_fp and context_fp do not depend
 * on the rename map at all, and weak_fp only needs it for the specific case of a file that both
 * moved <em>and</em> changed enough to defeat the other two rungs - so running without GitHub
 * configured is a real, supported mode (e.g. local development, or a project on GitLab/Bitbucket,
 * §11), not a degraded stub masquerading as a feature.
 */
public final class NoopScmRenameResolver implements ScmRenameResolver {

    @Override
    public Map<String, String> renamesBetween(
            String owner, String repo, String baseCommitSha, String headCommitSha) {
        return Map.of();
    }
}
