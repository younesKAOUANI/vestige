package dev.youneskaouani.vestige.ingestion.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with full jitter, exactly as §4.2 specifies it:
 *
 * <pre>{@code delay = min(2^n * baseDelay, maxDelay) * U(0, 1)}</pre>
 *
 * <p>"Full" jitter - multiplying the whole capped delay by a fresh {@code U(0,1)} draw, rather
 * than adding a smaller random offset on top of it - is deliberate and is the AWS Architecture
 * Blog's own recommendation for this exact problem. A database blip that fails fifty jobs in the
 * same instant must not have them all retry in the same instant too; that is how a system that is
 * already recovering gets knocked over a second time. Multiplying by {@code U(0,1)} spreads the
 * fiftieth retry anywhere between 0 and the full capped delay, which is a much wider spread than
 * adding a small jitter term to an otherwise-fixed delay would give.
 *
 * <p>The one cost of true randomness is that the exact delay is not reproducible from the job's
 * identity alone, unlike the matcher (§3.3), which must be deterministic because it replays. Retry
 * timing has no such requirement - a job succeeds or does not regardless of exactly when it was
 * retried - so the jitter source is a plain {@link ThreadLocalRandom} by default, with the
 * constructor left package-visible so a test can inject a fixed source and assert an exact delay.
 *
 * @param baseDelay delay before the first retry, before jitter
 * @param maxDelay ceiling for the delay, before jitter
 * @param maxAttempts attempts after which a job is declared dead and quarantined (§4.2)
 */
public record RetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts, DoubleSupplier jitterSource) {

    public RetryPolicy {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be smaller than baseDelay");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    /** §4.2's exact parameters: 250ms base, 5 minute cap, 5 attempts before quarantine. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(
                Duration.ofMillis(250),
                Duration.ofMinutes(5),
                5,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public static RetryPolicy of(Duration baseDelay, Duration maxDelay, int maxAttempts) {
        return new RetryPolicy(baseDelay, maxDelay, maxAttempts, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** A copy of this policy whose jitter is deterministic - for tests that need an exact delay. */
    public RetryPolicy withJitterSource(DoubleSupplier fixedJitterSource) {
        return new RetryPolicy(baseDelay, maxDelay, maxAttempts, fixedJitterSource);
    }

    /** True when a job that has made {@code attemptCount} attempts may be retried again. */
    public boolean shouldRetry(int attemptCount) {
        return attemptCount < maxAttempts;
    }

    /** When the next attempt after {@code attemptCount} failed ones should run. */
    public Instant nextAttemptAt(Instant now, int attemptCount) {
        return now.plus(delayFor(attemptCount));
    }

    /** The full-jitter backoff delay after {@code attemptCount} failed attempts. */
    public Duration delayFor(int attemptCount) {
        int exponent = Math.max(0, attemptCount - 1);
        long baseMillis = baseDelay.toMillis();
        long capMillis = maxDelay.toMillis();
        // Shifting past 62 overflows a long; anything beyond the cap is the cap anyway.
        long capped = exponent >= 62 ? capMillis : Math.min(capMillis, baseMillis << exponent);

        double jitter = jitterSource.getAsDouble();
        if (jitter < 0.0 || jitter >= 1.0) {
            throw new IllegalStateException("jitterSource must return a value in [0, 1), got " + jitter);
        }
        return Duration.ofMillis(Math.round(capped * jitter));
    }
}
