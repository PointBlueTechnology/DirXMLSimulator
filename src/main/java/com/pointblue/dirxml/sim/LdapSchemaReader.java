package com.pointblue.dirxml.sim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link SchemaModel} from the <b>eDirectory LDAP subschema</b> (the
 * RFC 4512 {@code attributeTypes}/{@code objectClasses} definitions on
 * {@code cn=schema}) — so a live LDAP connection can populate the schema directly,
 * with no Designer project.
 *
 * <p>eDir publishes the true NDS/DirXML name as the {@code X-NDS_NAME} schema
 * extension whenever it differs from the LDAP name; where absent, the NDS name is
 * the LDAP name. That gives the full NDS↔LDAP mapping, identical to a Designer
 * {@code *_schema.xml}. The LDAP syntax OID maps to the eDir {@code syn=} the value
 * normalizer keys on (table derived from real eDir + IDM schemas).
 *
 * <p>Parsing is done by emitting the harness's internal {@code <schema>} format and
 * delegating to {@link SchemaModel#parse} — one model, one validator.
 */
public final class LdapSchemaReader {

    /** LDAP/eDir syntax OID → eDir {@code syn=} name (empirically cross-referenced). */
    private static final Map<String, String> SYNTAX = Map.ofEntries(
        // RFC 4517 / X.500 syntaxes
        Map.entry("1.3.6.1.4.1.1466.115.121.1.12", "dist-name"),    // DN
        Map.entry("1.3.6.1.4.1.1466.115.121.1.7", "boolean"),
        Map.entry("1.3.6.1.4.1.1466.115.121.1.27", "integer"),
        Map.entry("1.3.6.1.4.1.1466.115.121.1.15", "ci-string"),    // Directory String
        Map.entry("1.3.6.1.4.1.1466.115.121.1.26", "ce-string"),    // IA5 String
        Map.entry("1.3.6.1.4.1.1466.115.121.1.44", "pr-string"),    // Printable String
        Map.entry("1.3.6.1.4.1.1466.115.121.1.36", "nu-string"),    // Numeric String
        Map.entry("1.3.6.1.4.1.1466.115.121.1.40", "octet-string"), // Octet String
        Map.entry("1.3.6.1.4.1.1466.115.121.1.5", "stream"),        // Binary (eDir: stream)
        Map.entry("1.3.6.1.4.1.1466.115.121.1.24", "time"),         // Generalized Time
        Map.entry("1.3.6.1.4.1.1466.115.121.1.50", "tel-number"),
        Map.entry("1.3.6.1.4.1.1466.115.121.1.22", "fax-number"),
        Map.entry("1.3.6.1.4.1.1466.115.121.1.41", "po-address"),
        Map.entry("1.3.6.1.4.1.1466.115.121.1.38", "class-name"),
        // eDir-specific syntaxes (arc 2.16.840.1.113719.1.1.5.1.N)
        Map.entry("2.16.840.1.113719.1.1.5.1.6", "ci-list"),
        Map.entry("2.16.840.1.113719.1.1.5.1.12", "net-address"),
        Map.entry("2.16.840.1.113719.1.1.5.1.13", "octet-list"),
        Map.entry("2.16.840.1.113719.1.1.5.1.14", "email-address"),
        Map.entry("2.16.840.1.113719.1.1.5.1.15", "path"),
        Map.entry("2.16.840.1.113719.1.1.5.1.16", "replica-pointer"),
        Map.entry("2.16.840.1.113719.1.1.5.1.17", "object-acl"),
        Map.entry("2.16.840.1.113719.1.1.5.1.19", "timestamp"),
        Map.entry("2.16.840.1.113719.1.1.5.1.22", "counter"),
        Map.entry("2.16.840.1.113719.1.1.5.1.23", "back-link"),
        Map.entry("2.16.840.1.113719.1.1.5.1.25", "typed-name"),
        Map.entry("2.16.840.1.113719.1.1.5.1.26", "hold"),
        Map.entry("2.16.840.1.113719.1.1.5.1.27", "interval"));

    private static final Pattern NAME = Pattern.compile(
        "\\bNAME\\s+(?:\\(\\s*((?:'[^']*'\\s*)+)\\)|'([^']*)')");
    private static final Pattern XNDS_NAME = Pattern.compile("X-NDS_NAME\\s+'([^']*)'");
    private static final Pattern SYN = Pattern.compile("\\bSYNTAX\\s+([0-9.]+)");
    private static final Pattern SUP = Pattern.compile(
        "\\bSUP\\s+(?:\\(\\s*([^)]+)\\)|([\\w.-]+))");
    private static final Pattern MUST = Pattern.compile(
        "\\bMUST\\s+(?:\\(\\s*([^)]+)\\)|([\\w.-]+))");
    private static final Pattern MAY = Pattern.compile(
        "\\bMAY\\s+(?:\\(\\s*([^)]+)\\)|([\\w.-]+))");

    private LdapSchemaReader() {}

    /** Parse subschema definitions (the values of {@code attributeTypes}/{@code objectClasses}). */
    public static SchemaModel parse(List<String> attributeTypes, List<String> objectClasses) {
        // ldap name (lower) -> NDS name, so class MUST/MAY (LDAP-named) can resolve to NDS names
        Map<String, String> ndsByLdap = new java.util.HashMap<>();
        StringBuilder xml = new StringBuilder("<schema>");
        for (String def : attributeTypes) {
            String[] names = names(def);
            if (names.length == 0) {
                continue;
            }
            String ldap = names[0];
            String nds = ndsName(def, ldap);
            ndsByLdap.put(ldap.toLowerCase(), nds);
            String syn = SYNTAX.getOrDefault(find(SYN, def), "ci-string");
            boolean single = def.contains("SINGLE-VALUE");
            xml.append("<attr name=\"").append(esc(nds)).append("\" ldap=\"").append(esc(ldap))
               .append("\" syn=\"").append(esc(syn)).append('"');
            if (single) {
                xml.append(" sngl=\"1\"");
            }
            xml.append("/>");
        }
        for (String def : objectClasses) {
            String[] names = names(def);
            if (names.length == 0) {
                continue;
            }
            String ldap = names[0];
            String nds = ndsName(def, ldap);
            xml.append("<class name=\"").append(esc(nds)).append("\" ldap=\"").append(esc(ldap)).append("\">");
            for (String sup : list(SUP, def)) {
                xml.append("<sup>").append(esc(sup)).append("</sup>");
            }
            // allowed attrs = MUST ∪ MAY; emit both the LDAP name and its NDS name
            Set<String> allowed = new LinkedHashSet<>();
            for (String a : list(MUST, def)) {
                addBoth(allowed, a, ndsByLdap);
            }
            for (String a : list(MAY, def)) {
                addBoth(allowed, a, ndsByLdap);
            }
            if (!allowed.isEmpty()) {
                xml.append("<opt>").append(esc(String.join(",", allowed))).append("</opt>");
            }
            xml.append("</class>");
        }
        xml.append("</schema>");
        return SchemaModel.parse(Xds.parse(xml.toString()));
    }

    /** Parse from raw subschema LDIF text (lines like {@code attributeTypes: ( … )}). */
    public static SchemaModel parseLdif(String ldif) {
        List<String> attrs = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        for (String logical : unfold(ldif)) {
            int c = logical.indexOf(':');
            if (c < 0) {
                continue;
            }
            String name = logical.substring(0, c).trim();
            String value = logical.substring(c + 1).trim();
            if (name.equalsIgnoreCase("attributeTypes")) {
                attrs.add(value);
            } else if (name.equalsIgnoreCase("objectClasses")) {
                classes.add(value);
            }
        }
        return parse(attrs, classes);
    }

    // ---- field extraction ----------------------------------------------

    private static String[] names(String def) {
        Matcher m = NAME.matcher(def);
        if (!m.find()) {
            return new String[0];
        }
        if (m.group(2) != null) {
            return new String[]{m.group(2)};
        }
        List<String> out = new ArrayList<>();
        Matcher q = Pattern.compile("'([^']*)'").matcher(m.group(1));
        while (q.find()) {
            out.add(q.group(1));
        }
        return out.toArray(new String[0]);
    }

    /** NDS name = X-NDS_NAME if present, else the LDAP name (eDir only emits it when they differ). */
    private static String ndsName(String def, String ldap) {
        String x = find(XNDS_NAME, def);
        return x != null ? x : ldap;
    }

    private static void addBoth(Set<String> set, String ldapAttr, Map<String, String> ndsByLdap) {
        set.add(ldapAttr);
        String nds = ndsByLdap.get(ldapAttr.toLowerCase());
        if (nds != null && !nds.equalsIgnoreCase(ldapAttr)) {
            set.add(nds);
        }
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** A SUP/MUST/MAY value: a single descriptor or a {@code $}-separated list. */
    private static List<String> list(Pattern p, String def) {
        Matcher m = p.matcher(def);
        List<String> out = new ArrayList<>();
        if (!m.find()) {
            return out;
        }
        String body = m.group(1) != null ? m.group(1) : m.group(2);
        for (String tok : body.split("\\$")) {
            String t = tok.trim().replace("'", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> unfold(String ldif) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : ldif.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (raw.startsWith(" ")) {
                if (cur != null) {
                    cur.append(raw, 1, raw.length());
                }
            } else {
                if (cur != null) {
                    out.add(cur.toString());
                }
                cur = new StringBuilder(raw);
            }
        }
        if (cur != null) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
