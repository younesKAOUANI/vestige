package dev.youneskaouani.vestige.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.youneskaouani.vestige.common.hash.CanonicalJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    @DisplayName("orders object keys deterministically regardless of insertion order")
    void ordersKeys() {
        Map<String, Object> inserted = new LinkedHashMap<>();
        inserted.put("zeta", 1);
        inserted.put("alpha", 2);
        inserted.put("Mike", 3);

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("Mike", 3);
        reversed.put("alpha", 2);
        reversed.put("zeta", 1);

        String expected = "{\"Mike\":3,\"alpha\":2,\"zeta\":1}";
        assertThat(CanonicalJson.write(inserted)).isEqualTo(expected);
        assertThat(CanonicalJson.write(reversed)).isEqualTo(expected);
        assertThat(CanonicalJson.write(new TreeMap<>(inserted))).isEqualTo(expected);
    }

    @Test
    @DisplayName("sorts nested objects too")
    void ordersNestedKeys() {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", "2");
        nested.put("a", "1");
        payload.put("outer", nested);
        payload.put("first", List.of(nested, nested));

        assertThat(CanonicalJson.write(payload))
                .isEqualTo("{\"first\":[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"1\",\"b\":\"2\"}],"
                        + "\"outer\":{\"a\":\"1\",\"b\":\"2\"}}");
    }

    @Test
    @DisplayName("preserves array order, which is semantically significant")
    void preservesArrayOrder() {
        assertThat(CanonicalJson.write(Map.of("xs", List.of(3, 1, 2))))
                .isEqualTo("{\"xs\":[3,1,2]}");
    }

    @Test
    @DisplayName("uses the shortest legal escape and escapes control characters")
    void escapesStrings() {
        assertThat(CanonicalJson.write(Map.of("k", "a\"b\\c\nd\te\u0001f")))
                .isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\te\\u0001f\"}");
    }

    @Test
    @DisplayName("keeps non-ASCII characters literal rather than escaping them")
    void keepsUnicodeLiteral() {
        assertThat(CanonicalJson.write(Map.of("actor", "Genève"))).isEqualTo("{\"actor\":\"Genève\"}");
    }

    @Test
    @DisplayName("serialises the scalar types the audit payload uses")
    void writesScalars() {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("s", "x");
        payload.put("b", Boolean.TRUE);
        payload.put("n", null);
        payload.put("i", 42);
        payload.put("l", 9_000_000_000L);
        payload.put("d", new BigDecimal("1.500"));

        assertThat(CanonicalJson.write(payload))
                .isEqualTo("{\"b\":true,\"d\":1.5,\"i\":42,\"l\":9000000000,\"n\":null,\"s\":\"x\"}");
    }

    @Test
    @DisplayName("rejects binary floating point rather than guessing a canonical form")
    void rejectsDoubles() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("x", 0.1d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported type");
    }

    @Test
    @DisplayName("rejects non-string object keys")
    void rejectsNonStringKeys() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of(1, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strings");
    }
}
