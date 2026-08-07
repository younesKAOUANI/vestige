package dev.youneskaouani.vestige.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.hash.HashChain;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the one property that makes §6's chain verifiable at all: an event hashes the same before it
 * is written and after it is read back.
 *
 * <p>{@code Instant.now()} carries nanoseconds on a modern JVM and PostgreSQL's {@code timestamptz}
 * keeps only microseconds, so an event hashed at its original precision produces one digest going
 * in and a different one coming out - and because the database <em>rounds</em> where {@code
 * truncatedTo} <em>truncates</em>, it only diverges when the sub-microsecond remainder is 500ns or
 * more. That is the worst shape a bug can have: roughly half of all chains report themselves broken
 * and the other half look perfectly healthy. {@code VestigeAuditTamperDetectionIT} does exercise
 * this against a real database, but only against whatever precision the clock happened to produce
 * on that run; these cases choose the remainder deliberately, so the boundary is checked every time
 * and without Docker.
 */
class TriageEventPrecisionTest {

    private static final UUID ISSUE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @ParameterizedTest(name = "sub-microsecond remainder of {0}ns survives the round trip")
    @ValueSource(ints = {0, 1, 499, 500, 501, 999})
    @DisplayName("the hashed payload ignores precision the database cannot store, on both sides of 500ns")
    void payloadIsStableAcrossPrecisionTheStoreCannotKeep(int subMicrosecondNanos) {
        Instant precise = Instant.parse("2026-01-01T00:00:00Z").plusNanos(123_456_000L + subMicrosecondNanos);
        Instant asStored = precise.truncatedTo(ChronoUnit.MICROS);

        assertThat(payloadOf(precise)).isEqualTo(payloadOf(asStored));
        assertThat(hashOf(precise)).isEqualTo(hashOf(asStored));
    }

    @Test
    @DisplayName("a constructed event holds an occurredAt PostgreSQL can round-trip without rounding")
    void constructorNormalisesOccurredAtToMicroseconds() {
        Instant precise = Instant.parse("2026-01-01T00:00:00Z").plusNanos(123_456_789L);

        TriageEvent event = eventAt(precise);

        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(event.getOccurredAt().getNano() % 1_000).isZero();
    }

    /**
     * The end-to-end shape of the bug: hash an event as the appender does, re-read it at the
     * precision the database keeps, and re-hash it as {@code AuditChainVerifier} does.
     */
    @Test
    @DisplayName("re-hashing an event after a microsecond round trip reproduces the original entry hash")
    void rehashingAfterAStoreRoundTripMatches() {
        Instant precise = Instant.parse("2026-01-01T00:00:00Z").plusNanos(123_456_789L);
        TriageEvent appended = eventAt(precise);

        String atAppendTime = HashChain.entryHash(HashChain.GENESIS_HASH, appended.canonicalPayload());
        String afterRoundTrip =
                HashChain.entryHash(HashChain.GENESIS_HASH, eventAt(appended.getOccurredAt()).canonicalPayload());

        assertThat(afterRoundTrip).isEqualTo(atAppendTime);
    }

    private static Object payloadOf(Instant occurredAt) {
        return TriageEvent.canonicalPayload(
                ISSUE_ID, "younes", IssueStatus.OPEN, IssueStatus.RESOLVED_WONT_FIX, "accepted risk", occurredAt);
    }

    private static String hashOf(Instant occurredAt) {
        return HashChain.entryHash(
                HashChain.GENESIS_HASH,
                TriageEvent.canonicalPayload(
                        ISSUE_ID, "younes", IssueStatus.OPEN, IssueStatus.RESOLVED_WONT_FIX, "accepted risk",
                        occurredAt));
    }

    private static TriageEvent eventAt(Instant occurredAt) {
        return new TriageEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ISSUE_ID,
                1L,
                "younes",
                IssueStatus.OPEN,
                IssueStatus.RESOLVED_WONT_FIX,
                "accepted risk",
                occurredAt,
                HashChain.GENESIS_HASH,
                "unused-for-this-assertion");
    }
}
