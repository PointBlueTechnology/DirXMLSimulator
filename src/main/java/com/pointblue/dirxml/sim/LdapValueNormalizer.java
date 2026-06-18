package com.pointblue.dirxml.sim;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Normalizes a value read from eDirectory <em>over LDAP/JNDI</em> into the form
 * the engine's native query seam would return, keyed on the attribute's eDir
 * <b>syntax</b> (the schema's {@code syn=}).
 *
 * <p>This is the piece a naive LDAP-backed query processor gets wrong: JNDI hands
 * back {@code String} or {@code byte[]} and a plain {@code toString()} diverges
 * from native XDS for several syntaxes — octet values become {@code [B@…}
 * garbage instead of base64, eDir time comes back as generalized time instead of
 * integer seconds, DN-syntax values stay in LDAP form, and {@code path}/
 * {@code typed-name} stay flat instead of structured. See
 * {@code docs/shim-testing-design.md} for the full table.
 *
 * <p>The normalizer is DOM-free and side-effect-free so it can be unit-tested
 * without a directory; callers ({@code LdapQueryProcessor}) render the returned
 * {@link Value} into an XDS {@code <value>} element.
 */
public final class LdapValueNormalizer {

    /** A named component of a structured value (e.g. {@code path}'s nameSpace/volume/path). */
    public static final class Component {
        public final String name;
        public final String text;
        public Component(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    /** The normalized value: a scalar (type + text) or a structured set of components. */
    public static final class Value {
        /** XDS {@code type=} attribute, or {@code null} for a plain string value. */
        public final String type;
        /** Scalar text content; {@code null} when {@link #structured()} or {@link #skip}. */
        public final String text;
        /** Components when {@code type=="structured"}; empty otherwise. */
        public final List<Component> components;
        /** When true the value should be omitted entirely (e.g. a {@code stream}). */
        public final boolean skip;

        private Value(String type, String text, List<Component> components, boolean skip) {
            this.type = type;
            this.text = text;
            this.components = components == null ? List.of() : components;
            this.skip = skip;
        }
        static Value scalar(String type, String text) { return new Value(type, text, null, false); }
        static Value structured(List<Component> c) { return new Value("structured", null, c, false); }
        static final Value SKIP = new Value(null, null, null, true);

        public boolean structured() { return "structured".equals(type); }
    }

    /** Generalized time: {@code yyyyMMddHHmmss}, optional fraction, optional zone (default UTC). */
    private static final DateTimeFormatter GENERALIZED_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmmss")
            .optionalStart().appendFraction(ChronoField.MICRO_OF_SECOND, 1, 6, true).optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
            .toFormatter();

    /** Tree name to prepend to slash-form DNs, or {@code null} to omit (most dest queries). */
    private final String dnTreeName;

    public LdapValueNormalizer() { this(null); }

    public LdapValueNormalizer(String dnTreeName) {
        this.dnTreeName = (dnTreeName == null || dnTreeName.isEmpty()) ? null : dnTreeName;
    }

    /**
     * Normalize one LDAP value for an attribute of the given syntax.
     *
     * @param syntax    the schema {@code syn=} (e.g. {@code octet-string}); {@code null} ⇒ string
     * @param ldapValue a JNDI value — {@code String} or {@code byte[]}
     */
    public Value normalize(String syntax, Object ldapValue) {
        String syn = syntax == null ? "" : syntax.toLowerCase();
        switch (syn) {
            case "octet-string":
            case "octet-list":
            case "net-address":
                return Value.scalar("octet", base64(ldapValue));

            case "stream":
                return Value.SKIP;

            case "boolean":
                return Value.scalar("string", normalizeBoolean(asString(ldapValue)));

            case "time":
                return Value.scalar("time", generalizedTimeToSeconds(asString(ldapValue)));

            case "timestamp":
                // eDir Timestamp = whole seconds + replica event; LDAP gives only the
                // seconds portion. Synthesize a #0 event id so the shape matches native.
                return Value.scalar("string", generalizedTimeToSeconds(asString(ldapValue)) + "#0");

            case "dist-name":
            case "class-name":  // class-name is a plain string, falls through below if not dist
                if (syn.equals("dist-name")) {
                    return Value.scalar(null, ldapDnToSlash(asString(ldapValue)));
                }
                return Value.scalar(null, asString(ldapValue));

            case "path":
                return pathValue(asString(ldapValue));

            case "typed-name":
                return typedNameValue(asString(ldapValue));

            default:
                // string family (ci/ce/pr/nu-string, tel/fax/email, integer, counter,
                // interval, …) and unknown: native and LDAP agree.
                return Value.scalar(null, asString(ldapValue));
        }
    }

    // ---- syntax helpers -------------------------------------------------

    private static String asString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        return v.toString();
    }

    private static String base64(Object v) {
        byte[] bytes = (v instanceof byte[]) ? (byte[]) v
                : asString(v).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String normalizeBoolean(String s) {
        return "true".equalsIgnoreCase(s.trim()) || "TRUE".equals(s) ? "true" : "false";
    }

    /** Generalized time ({@code 20240101000000Z}) → integer epoch seconds; pass through on failure. */
    static String generalizedTimeToSeconds(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            OffsetDateTime odt = OffsetDateTime.parse(t, GENERALIZED_TIME);
            return Long.toString(odt.toEpochSecond());
        } catch (RuntimeException e) {
            // already integer seconds, or an unrecognized form — leave as-is
            return t;
        }
    }

    /**
     * LDAP DN ({@code cn=Role,ou=idm,o=system}) → eDir slash form
     * ({@code \system\idm\Role}), reversing RDNs and stripping {@code cn=}/{@code ou=}/{@code o=}.
     */
    String ldapDnToSlash(String dn) {
        if (dn == null || dn.isEmpty()) {
            return "";
        }
        String[] rdns = dn.split(",");
        StringBuilder sb = new StringBuilder();
        if (dnTreeName != null) {
            sb.append('\\').append(dnTreeName);
        }
        for (int i = rdns.length - 1; i >= 0; i--) {
            String rdn = rdns[i].trim();
            int eq = rdn.indexOf('=');
            sb.append('\\').append(eq >= 0 ? rdn.substring(eq + 1) : rdn);
        }
        return sb.toString();
    }

    /** eDir Path syntax: LDAP {@code nameSpace#volume#path} → structured components. */
    private Value pathValue(String s) {
        String[] parts = s.split("#", 3);
        if (parts.length < 3) {
            return Value.scalar(null, s); // not in the expected form — leave flat
        }
        List<Component> c = new ArrayList<>(3);
        c.add(new Component("nameSpace", parts[0]));
        c.add(new Component("volume", parts[1]));
        c.add(new Component("path", parts[2]));
        return Value.structured(c);
    }

    /** eDir Typed Name syntax: LDAP {@code dn#level#interval} → structured components. */
    private Value typedNameValue(String s) {
        String[] parts = s.split("#", 3);
        if (parts.length < 3) {
            return Value.scalar(null, s);
        }
        List<Component> c = new ArrayList<>(3);
        c.add(new Component("typedName", ldapDnToSlash(parts[0])));
        c.add(new Component("level", parts[1]));
        c.add(new Component("interval", parts[2]));
        return Value.structured(c);
    }
}
