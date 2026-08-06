package dev.youneskaouani.vestige.common;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.hash.HashChain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashChainTest {

    private static Map<String, Object> payload(String toStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issueId", "11111111-1111-1111-1111-111111111111");
        payload.put("fromStatus", "OPEN");
        payload.put("toStatus", toStatus);
        payload.put("actor", "younes");
        payload.put("reason", "reviewed and accepted");
        payload.put("createdAt", "2026-03-01T10:15:30Z");
        return payload;
    }

    @Test
    @DisplayName("is a pure function of the previous hash and the payload")
    void isDeterministic() {
        assertThat(HashChain.entryHash("abc", payload("ACCEPTED")))
                .isEqualTo(HashChain.entryHash("abc", payload("ACCEPTED")));
    }

    @Test
    @DisplayName("ignores the order the payload keys were inserted in")
    void ignoresKeyInsertionOrder() {
        assertThat(HashChain.entryHash("abc", new TreeMap<>(payload("ACCEPTED"))))
                .isEqualTo(HashChain.entryHash("abc", payload("ACCEPTED")));
    }

    @Test
    @DisplayName("treats a missing previous hash as the genesis constant")
    void usesGenesisForFirstEntry() {
        String expected = HashChain.entryHash(HashChain.GENESIS_HASH, payload("ACCEPTED"));
        assertThat(HashChain.entryHash(null, payload("ACCEPTED"))).isEqualTo(expected);
        assertThat(HashChain.entryHash("  ", payload("ACCEPTED"))).isEqualTo(expected);
    }

    @Test
    @DisplayName("changes when the payload changes")
    void detectsPayloadTampering() {
        assertThat(HashChain.entryHash("abc", payload("ACCEPTED")))
                .isNotEqualTo(HashChain.entryHash("abc", payload("FALSE_POSITIVE")));
    }

    @Test
    @DisplayName("changes when the predecessor changes, which is what makes the chain evident")
    void detectsPredecessorTampering() {
        assertThat(HashChain.entryHash("abc", payload("ACCEPTED")))
                .isNotEqualTo(HashChain.entryHash("abd", payload("ACCEPTED")));
    }

    @Test
    @DisplayName("produces a 64-character lowercase hex digest")
    void producesHexDigest() {
        assertThat(HashChain.entryHash(null, payload("ACCEPTED"))).matches("[0-9a-f]{64}");
    }
}
