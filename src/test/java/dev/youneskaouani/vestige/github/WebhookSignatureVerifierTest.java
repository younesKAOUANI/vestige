package dev.youneskaouani.vestige.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.youneskaouani.vestige.github.service.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    @DisplayName("reproduces the signature from GitHub's own worked example")
    void matchesGitHubsPublishedVector() {
        assertThat(verifier.sign(PAYLOAD))
                .isEqualTo(
                        "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
    }

    @Test
    @DisplayName("accepts the signature it computes")
    void acceptsAValidSignature() {
        assertThat(verifier.isValid(PAYLOAD, verifier.sign(PAYLOAD))).isTrue();
    }

    @Test
    @DisplayName("rejects a signature computed over different bytes")
    void rejectsATamperedPayload() {
        String signature = verifier.sign(PAYLOAD);
        assertThat(verifier.isValid("Hello, World?".getBytes(StandardCharsets.UTF_8), signature))
                .isFalse();
    }

    @Test
    @DisplayName("rejects a signature made with a different secret")
    void rejectsTheWrongSecret() {
        String forged = new WebhookSignatureVerifier("not the secret").sign(PAYLOAD);
        assertThat(verifier.isValid(PAYLOAD, forged)).isFalse();
    }

    @Test
    @DisplayName("fails closed on an absent, unprefixed or truncated header")
    void failsClosed() {
        assertThat(verifier.isValid(PAYLOAD, null)).isFalse();
        assertThat(verifier.isValid(PAYLOAD, "")).isFalse();
        assertThat(
                        verifier.isValid(
                                PAYLOAD,
                                "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))
                .isFalse();
        assertThat(verifier.isValid(PAYLOAD, "sha1=abc")).isFalse();
        assertThat(verifier.isValid(PAYLOAD, verifier.sign(PAYLOAD).substring(0, 20))).isFalse();
        assertThat(verifier.isValid(null, verifier.sign(PAYLOAD))).isFalse();
    }

    @Test
    @DisplayName("signs an empty body rather than treating it as a special case")
    void handlesAnEmptyBody() {
        assertThat(verifier.isValid(new byte[0], verifier.sign(new byte[0]))).isTrue();
    }

    @Test
    @DisplayName(
            "refuses to be constructed without a secret, so it cannot silently allow everything")
    void requiresASecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignatureVerifier("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
