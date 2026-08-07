package dev.youneskaouani.vestige.common.hash;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON serialisation, used as the pre-image of every audit-chain entry hash.
 *
 * <p>The rules follow RFC 8785 (JSON Canonicalization Scheme) for the subset of JSON that Vestige
 * actually puts in the audit log:
 *
 * <ul>
 *   <li>object members are sorted by their UTF-16 code units, which is what RFC 8785 mandates and
 *       what {@link String#compareTo(String)} already implements;
 *   <li>no insignificant whitespace;
 *   <li>strings use the shortest legal escape;
 *   <li>only exact numbers are accepted.
 * </ul>
 *
 * <p>Binary floating point is deliberately rejected rather than canonicalised. The audit payloads
 * contain identifiers, timestamps and enum names; admitting {@code double} would buy nothing and
 * would make the chain depend on the subtleties of shortest-round-trip formatting.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * Serialises a JSON value built from {@link Map}, {@link Collection}, {@link String},
     * {@link Boolean}, exact numbers and {@code null}.
     *
     * @throws IllegalArgumentException if the value contains an unsupported type
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(128);
        writeValue(value, out);
        return out.toString();
    }

    // An if/else ladder rather than a pattern switch: the language level here is 17
    // (maven.compiler.release), and patterns in switch are only final in 21. The ordering is the
    // same as the switch it replaces, and it matters in one place - Enum<?> is tested after the
    // concrete types so that a hypothetical enum implementing Collection cannot be caught by the
    // wrong arm.
    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Integer i) {
            out.append(i.intValue());
        } else if (value instanceof Long l) {
            out.append(l.longValue());
        } else if (value instanceof Short s) {
            out.append(s.shortValue());
        } else if (value instanceof Byte b) {
            out.append(b.byteValue());
        } else if (value instanceof BigInteger bi) {
            out.append(bi.toString());
        } else if (value instanceof BigDecimal bd) {
            out.append(bd.stripTrailingZeros().toPlainString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, out);
        } else if (value instanceof Enum<?> e) {
            writeString(e.name(), out);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported type in canonical JSON payload: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String s)) {
                throw new IllegalArgumentException("Canonical JSON object keys must be strings, got: " + key);
            }
            keys.add(s);
        }
        keys.sort(String::compareTo);

        out.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(key, out);
            out.append(':');
            writeValue(map.get(key), out);
        }
        out.append('}');
    }

    private static void writeArray(Collection<?> collection, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object element : collection) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(element, out);
        }
        out.append(']');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
