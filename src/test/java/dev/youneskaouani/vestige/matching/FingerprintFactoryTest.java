package dev.youneskaouani.vestige.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FingerprintFactoryTest {

    @Test
    @DisplayName("computes all three fingerprints when every input is present")
    void computesAllThreeWhenFullyPopulated() {
        Fingerprints fp =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");

        assertThat(fp.identityFp()).isNotBlank();
        assertThat(fp.contextFp()).isNotBlank();
        assertThat(fp.weakFp()).isNotBlank();
    }

    @Test
    @DisplayName(
            "identity_fp is null without a symbol path, and matching falls back to rung 2 (§3.2)")
    void identityFpIsNullWithoutASymbolPath() {
        Fingerprints fp =
                FingerprintFactory.compute(
                        "java:S3649", "src/PaymentService.java", null, "String sql = x;");

        assertThat(fp.identityFp()).isNull();
        assertThat(fp.contextFp()).isNotBlank();
    }

    @Test
    @DisplayName("context_fp is null without a line snippet")
    void contextFpIsNullWithoutASnippet() {
        Fingerprints fp =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        null);

        assertThat(fp.contextFp()).isNull();
        assertThat(fp.identityFp()).isNotBlank();
    }

    @Test
    @DisplayName("weak_fp is always present: only the rule id and file path are mandatory")
    void weakFpIsAlwaysPresent() {
        Fingerprints fp =
                FingerprintFactory.compute("java:S3649", "src/PaymentService.java", null, null);

        assertThat(fp.weakFp()).isNotBlank();
    }

    @Test
    @DisplayName(
            "identity_fp survives a line move (line number is not an input to any fingerprint)")
    void identityFpIgnoresLineNumber() {
        // The fingerprint functions never take a line number; §3.2 handles line movement by
        // fingerprinting content, not position. This test documents that as an invariant.
        Fingerprints run1 =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "line 42 content");
        Fingerprints run2 =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "line 58 content");

        assertThat(run1.identityFp()).isEqualTo(run2.identityFp());
    }

    @Test
    @DisplayName(
            "two different rules on the same line produce different fingerprints at every rung")
    void differentRulesNeverCollide() {
        Fingerprints a =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");
        Fingerprints b =
                FingerprintFactory.compute(
                        "java:S2259",
                        "src/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");

        assertThat(a.identityFp()).isNotEqualTo(b.identityFp());
        assertThat(a.contextFp()).isNotEqualTo(b.contextFp());
        assertThat(a.weakFp()).isNotEqualTo(b.weakFp());
    }

    @Test
    @DisplayName("a renamed file changes every fingerprint unless the caller supplies the new path")
    void renamedFileChangesEveryFingerprintUnlessRenameIsApplied() {
        Fingerprints before =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/old/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");
        Fingerprints afterRenameApplied =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/new/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");

        assertThat(before.identityFp()).isNotEqualTo(afterRenameApplied.identityFp());

        // But recomputing the *previous* side's fingerprint against the renamed path - exactly
        // what IssueMatchingService does before calling IssueMatcher - restores the match.
        Fingerprints previousWithRenameApplied =
                FingerprintFactory.compute(
                        "java:S3649",
                        "src/new/PaymentService.java",
                        "com.acme.PaymentService#refund",
                        "String sql = x;");
        assertThat(previousWithRenameApplied.identityFp())
                .isEqualTo(afterRenameApplied.identityFp());
    }

    @Test
    @DisplayName("requires a rule id, since every fingerprint depends on it")
    void requiresARuleId() {
        assertThatThrownBy(() -> FingerprintFactory.compute(null, "a.java", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FingerprintFactory.compute("  ", "a.java", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
