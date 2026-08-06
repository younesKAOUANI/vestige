package dev.youneskaouani.vestige.github.config;

import dev.youneskaouani.vestige.common.config.VestigeProperties;
import dev.youneskaouani.vestige.github.service.GitHubScmRenameResolver;
import dev.youneskaouani.vestige.github.service.NoopScmRenameResolver;
import dev.youneskaouani.vestige.github.service.ScmRenameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Chooses the {@link ScmRenameResolver} implementation from {@code vestige.github.token}.
 *
 * <p>GitHub's compare API works unauthenticated for public repositories, so a blank token would
 * not strictly have to mean "never call it" - but making an outbound call to a third-party API by
 * default, for an operator who never configured GitHub integration at all, is the wrong default.
 * A blank token is treated as "GitHub integration is off" across the board (renames here, Check
 * Runs in the gate module), which is the simpler contract to document and to reason about.
 *
 * <p>Plain imperative choice rather than a pair of {@code @ConditionalOnProperty} beans: that
 * annotation's default {@code havingValue} matches on "resolves to anything other than the literal
 * string false", which treats an explicitly-empty {@code vestige.github.token=} the same as an
 * absent one only with the exact right combination of {@code havingValue}/{@code matchIfMissing} -
 * a subtlety not worth the risk in code that (see README, "A note on how this was built") could
 * not be compiled against the real Spring context to check.
 */
@Configuration
public class GitHubConfig {

    @Bean
    public ScmRenameResolver scmRenameResolver(RestClient.Builder restClientBuilder, VestigeProperties properties) {
        String token = properties.github().token();
        if (token == null || token.isBlank()) {
            return new NoopScmRenameResolver();
        }
        return new GitHubScmRenameResolver(restClientBuilder, token);
    }
}
