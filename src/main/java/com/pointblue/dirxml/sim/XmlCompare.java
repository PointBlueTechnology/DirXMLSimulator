package com.pointblue.dirxml.sim;

/**
 * Canonical XDS comparison for golden assertions. v1 normalizes whitespace
 * between elements (the Novell serializer is otherwise stable) and compares the
 * resulting text, reporting the first divergence. Attribute-order-insensitive
 * comparison is a planned enhancement.
 */
public final class XmlCompare {

    private XmlCompare() {}

    public static final class Diff {
        public final boolean equal;
        public final String message;
        Diff(boolean equal, String message) {
            this.equal = equal;
            this.message = message;
        }
    }

    /** Normalize: re-serialize, drop inter-tag whitespace, trim. */
    public static String canonical(String xml) {
        String reserialized = Xds.serialize(Xds.parse(xml));
        // collapse whitespace runs that sit entirely between tags
        return reserialized.replaceAll(">\\s+<", "><").trim();
    }

    public static Diff compare(String expected, String actual) {
        String e = canonical(expected);
        String a = canonical(actual);
        if (e.equals(a)) {
            return new Diff(true, "match");
        }
        int i = 0;
        int min = Math.min(e.length(), a.length());
        while (i < min && e.charAt(i) == a.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 40);
        String eCtx = e.substring(from, Math.min(e.length(), i + 40));
        String aCtx = a.substring(from, Math.min(a.length(), i + 40));
        return new Diff(false,
            "first difference at offset " + i + ":\n"
            + "  expected: …" + eCtx + "…\n"
            + "  actual:   …" + aCtx + "…");
    }
}
