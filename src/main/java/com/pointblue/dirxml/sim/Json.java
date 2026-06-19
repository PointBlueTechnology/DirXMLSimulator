package com.pointblue.dirxml.sim;

import java.util.List;

/**
 * Minimal JSON writer — just enough to emit the simulator's structured
 * (`--json`) output without pulling in a JSON dependency. Values are built as
 * strings and composed; {@link #q} handles escaping.
 */
final class Json {

    private Json() {
    }

    /** A JSON string literal (escaped, quoted), or {@code null} for a Java null. */
    static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** A JSON array from already-serialized element strings. */
    static String arr(List<String> elements) {
        return "[" + String.join(",", elements) + "]";
    }

    /** An array of JSON string literals from raw strings. */
    static String strArr(List<String> values) {
        return "[" + String.join(",", values.stream().map(Json::q).toList()) + "]";
    }

    /** A JSON object from {@code "key", serializedValue} pairs (values already JSON). */
    static String obj(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            b.append(q(kv[i])).append(':').append(kv[i + 1]);
        }
        return b.append('}').toString();
    }
}
