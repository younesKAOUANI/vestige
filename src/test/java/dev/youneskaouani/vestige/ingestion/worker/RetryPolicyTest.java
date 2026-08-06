package dev.youneskaouani.vestige.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static RetryPolicy fixedJitter(double jitter) {
        return RetryPolicy.defaults().withJitterSource(() -> jitter);
    }

    @Test
    @DisplayName("§4.2's exact parameters: 250ms base, 5 minute cap, 5 attempts")
    void defaultsMatchTheSpec() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.baseDelay()).isEqualTo(Duration.ofMillis(250));
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.maxAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("doubles the uncapped delay on each attempt: min(2^n * 250ms, 5min) before jitter")
    void doublesUncappedDelay() {
        // jitter fixed at 1 - epsilon isolates the exponential term from the multiplication.
        RetryPolicy policy = fixedJitter(0.999999);

        assertThat(policy.delayFor(1).toMillis()).isCloseTo(250, org.assertj.core.data.Offset.offset(1L));
        assertThat(policy.delayFor(2).toMillis()).isCloseTo(500, org.assertj.core.data.Offset.offset(1L));
        assertThat(policy.delayFor(3).toMillis()).isCloseTo(1000, org.assertj.core.data.Offset.offset(1L));
        assertThat(policy.delayFor(4).toMillis()).isCloseTo(2000, org.assertj.core.data.Offset.offset(2L));
    }

    @Test
    @DisplayName("never exceeds the cap, no matter how many attempts have been made")
    void neverExceedsTheCap() {
        RetryPolicy policy = fixedJitter(0.999999);

        assertThat(policy.delayFor(10).toMillis()).isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
        assertThat(policy.delayFor(1000).toMillis()).isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    @DisplayName("a jitter of exactly zero produces a zero delay")
    void zeroJitterProducesZeroDelay() {
        assertThat(fixedJitter(0.0).delayFor(1)).isEqualTo(Duration.ZERO);
        assertThat(fixedJitter(0.0).delayFor(5)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("scales the capped delay linearly with the jitter draw")
    void scalesLinearlyWithJitter() {
        // attempt 3: uncapped = 250 * 2^2 = 1000ms
        assertThat(fixedJitter(0.5).delayFor(3).toMillis()).isEqualTo(500L);
        assertThat(fixedJitter(0.25).delayFor(3).toMillis()).isEqualTo(250L);
    }

    @Test
    @DisplayName("rejects a jitter source that strays outside [0, 1)")
    void rejectsAnOutOfRangeJitterSource() {
        assertThatThrownBy(() -> fixedJitter(1.0).delayFor(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixedJitter(-0.1).delayFor(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("retries until, but not after, maxAttempts")
    void retriesUntilMaxAttempts() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.shouldRetry(0)).isTrue();
        assertThat(policy.shouldRetry(4)).isTrue();
        assertThat(policy.shouldRetry(5)).isFalse();
        assertThat(policy.shouldRetry(6)).isFalse();
    }

    @Test
    @DisplayName("adds the computed delay to the reference instant")
    void nextAttemptAddsDelayToNow() {
        RetryPolicy policy = fixedJitter(0.5);
        Instant now = Instant.parse("2026-08-06T12:00:00Z");

        assertThat(policy.nextAttemptAt(now, 3)).isEqualTo(now.plusMillis(500));
    }

    @Test
    @DisplayName("rejects a construction that could never produce a sane schedule")
    void validatesConstruction() {
        assertThatThrownBy(() -> RetryPolicy.of(Duration.ZERO, Duration.ofMinutes(1), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.of(Duration.ofMinutes(1), Duration.ofSeconds(1), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.of(Duration.ofMillis(1), Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
