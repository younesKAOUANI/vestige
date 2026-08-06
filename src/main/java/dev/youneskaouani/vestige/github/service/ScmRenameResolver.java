package dev.youneskaouani.vestige.github.service;

import java.util.Map;

/**
 * Resolves file renames between two commits (§3.2's "File rename handling"), so the previous
 * run's findings can be re-fingerprinted against a file's <em>current</em> path before matching.
 *
 * <p>Provider-shaped rather than GitHub-specific in name, matching §11's stated non-goal: GitLab
 * and Bitbucket adapters are out of scope for v1 ({@link GitHubScmRenameResolver} is the only
 * implementation that talks to a real API), but the seam is where they would attach.
 */
public interface ScmRenameResolver {

    /**
     * @return the commit's rename map, {@code previous path -> current path}, for every file the
     *     provider reports as renamed (not merely edited) between the two commits. Empty - never
     *     an exception - when there is no base commit to compare against, or when the provider is
     *     unreachable: §3.2 is explicit that this degrades gracefully rather than failing the run,
     *     because losing rename-awareness for one run is a false split, not data loss, and every
     *     implementation of this interface must preserve that guarantee.
     */
    Map<String, String> renamesBetween(String owner, String repo, String baseCommitSha, String headCommitSha);
}
