package com.pointblue.dirxml.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of mapping tables, keyed by table name, that the
 * {@code vnd.nds.stream:} URL handler serves to the engine when a {@code Map}
 * token resolves a table (see {@link NdsStreamProtocol} and the design note
 * {@code docs/mapping-tables-design.md}).
 *
 * <p>The engine builds an opaque {@code vnd.nds.stream:} URL whose last DN
 * component is the table name (e.g. {@code …/cn=LocCodeMap,cn=Library,…}). Rather
 * than parse that format exactly, {@link #resolve} scans the URL for any
 * <em>registered</em> table name as a whole DN component — robust to the precise
 * URL shape and to literal vs GCV-expanded references alike.
 */
final class MappingTableStore {

    private MappingTableStore() {
    }

    // name -> the <mapping-table> XML the engine should parse
    private static final Map<String, String> TABLES = new ConcurrentHashMap<>();

    /** Register a table's {@code <mapping-table>} XML under its name. */
    static void register(String name, String mappingTableXml) {
        if (name != null && !name.isBlank() && mappingTableXml != null) {
            TABLES.put(name, mappingTableXml);
        }
    }

    /** Forget all tables (between cases in a long-lived JVM). */
    static void clear() {
        TABLES.clear();
    }

    static boolean isEmpty() {
        return TABLES.isEmpty();
    }

    /** The {@code <mapping-table>} XML for a registered name, or null. */
    static String byName(String name) {
        return TABLES.get(name);
    }

    /**
     * Resolve a requested {@code vnd.nds.stream:} URL to a table's XML by finding a
     * registered table name that appears as a DN component in the URL.
     */
    static String resolve(String urlString) {
        if (urlString == null) {
            return null;
        }
        for (Map.Entry<String, String> e : TABLES.entrySet()) {
            if (mentions(urlString, e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** True if {@code name} appears in {@code url} as a whole DN component / path segment. */
    private static boolean mentions(String url, String name) {
        int from = 0;
        while (true) {
            int i = url.indexOf(name, from);
            if (i < 0) {
                return false;
            }
            char before = i == 0 ? '/' : url.charAt(i - 1);
            int end = i + name.length();
            char after = end >= url.length() ? '/' : url.charAt(end);
            // a DN component is bounded by '=' / '/' / ',' / '\' / end — not by an
            // alphanumeric (which would make this a partial-name match).
            if (isBoundary(before) && isBoundary(after)) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isBoundary(char c) {
        return c == '/' || c == '=' || c == ',' || c == '\\' || c == ':';
    }
}
