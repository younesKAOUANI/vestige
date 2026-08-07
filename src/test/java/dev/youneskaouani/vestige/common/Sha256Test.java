package dev.youneskaouani.vestige.common;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.hash.Sha256;
import dev.youneskaouani.vestige.common.util.ConstantTime;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Sha256Test {

    @Test
    @DisplayName("matches the published digest of the empty string")
    void matchesKnownVector() {
        assertThat(Sha256.hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("matches the published digest of \"abc\"")
    void matchesSecondKnownVector() {
        assertThat(Sha256.hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("hashes bytes and their UTF-8 string form identically")
    void hashesBytesAndStringsAlike() {
        assertThat(Sha256.hex("Genève".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Sha256.hex("Genève"));
    }

    @Test
    @DisplayName("keeps field boundaries unambiguous so shifted concatenations do not collide")
    void resistsFieldBoundaryCollisions() {
        assertThat(Sha256.hexOfFields("ab", "c")).isNotEqualTo(Sha256.hexOfFields("a", "bc"));
        assertThat(Sha256.hexOfFields("a", "", "b")).isNotEqualTo(Sha256.hexOfFields("a", "b"));
    }

    @Test
    @DisplayName("treats a null field as an empty field rather than throwing")
    void toleratesNullFields() {
        assertThat(Sha256.hexOfFields("a", null, "b")).isEqualTo(Sha256.hexOfFields("a", "", "b"));
    }

    @Test
    @DisplayName("constant-time comparison still agrees with equality")
    void constantTimeComparison() {
        assertThat(ConstantTime.equals("secret", "secret")).isTrue();
        assertThat(ConstantTime.equals("secret", "secreu")).isFalse();
        assertThat(ConstantTime.equals("secret", "secre")).isFalse();
        assertThat(ConstantTime.equals(null, "secret")).isFalse();
        assertThat(ConstantTime.equals("secret", null)).isFalse();
    }
}
